/*
 * The MIT License (MIT) Copyright (c) 2020-2021 artipie.com
 * https://github.com/artipie/conda-adapter/LICENSE
 */
package com.artipie.conda.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.ContentDigest;
import com.artipie.asto.ext.Digests;
import com.artipie.asto.misc.UncheckedIOScalar;
import com.artipie.asto.streams.ContentAsStream;
import com.artipie.conda.asto.AstoMergedJson;
import com.artipie.conda.meta.InfoIndex;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.async.AsyncResponse;
import com.artipie.http.headers.ContentDisposition;
import com.artipie.http.rq.RequestLineFrom;
import com.artipie.http.rq.multipart.RqMultipart;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.rs.RsWithStatus;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import org.reactivestreams.Publisher;

/**
 * Slice to update the repository.
 * @since 0.4
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class UpdateSlice implements Slice {

    /**
     * Regex to obtain uploaded package architecture and name from request line.
     */
    private static final Pattern PKG = Pattern.compile(".*/((.*)/(.*(\\.tar\\.bz2|\\.conda)))$");

    /**
     * Temporary upload key.
     */
    private static final Key TMP = new Key.From("./upload");

    /**
     * Abstract storage.
     */
    private final Storage asto;

    /**
     * Ctor.
     * @param asto Abstract storage
     */
    public UpdateSlice(final Storage asto) {
        this.asto = asto;
    }

    @Override
    public Response response(final String line, final Iterable<Map.Entry<String, String>> headers,
        final Publisher<ByteBuffer> body) {
        final Matcher matcher = UpdateSlice.PKG.matcher(new RequestLineFrom(line).uri().getPath());
        final Response res;
        if (matcher.matches()) {
            final Key temp = new Key.From(UpdateSlice.TMP, matcher.group(1));
            res = new AsyncResponse(
                this.asto.exists(new Key.From(matcher.group(1))).thenCompose(
                    main -> this.asto.exists(temp).thenApply(upl -> main || upl)
                ).thenCompose(
                    exists -> {
                        final CompletionStage<Response> resp;
                        if (exists) {
                            resp = CompletableFuture.completedFuture(
                                new RsWithStatus(RsStatus.BAD_REQUEST)
                            );
                        } else {
                            resp = this.asto.save(
                                temp,
                                new Content.From(
                                    UpdateSlice.filePart(new Headers.From(headers), body)
                                )
                            )
                                .thenCompose(empty -> this.infoJson(matcher.group(1), temp))
                                .thenCompose(json -> this.addChecksum(temp, Digests.MD5, json))
                                .thenCompose(json -> this.addChecksum(temp, Digests.SHA256, json))
                                .thenApply(JsonObjectBuilder::build)
                                .thenCompose(
                                    json -> new AstoMergedJson(
                                        this.asto, new Key.From(matcher.group(2), "repodata.json")
                                    ).merge(
                                        // @checkstyle MagicNumberCheck (3 lines)
                                        Collections.singletonMap(matcher.group(3), json)
                                    )
                                ).thenCompose(
                                    ignored -> this.asto.move(temp, new Key.From(matcher.group(1)))
                                ).thenApply(
                                    ignored -> new RsWithStatus(RsStatus.CREATED)
                                );
                        }
                        return resp;
                    }
                )
            );
        } else {
            res = new RsWithStatus(RsStatus.BAD_REQUEST);
        }
        return res;
    }

    /**
     * Adds checksum of the package to json.
     * @param key Package key
     * @param alg Digest algorithm
     * @param json Json to add value to
     * @return JsonObjectBuilder with added checksum as completion action
     */
    private CompletionStage<JsonObjectBuilder> addChecksum(final Key key, final Digests alg,
        final JsonObjectBuilder json) {
        return this.asto.value(key).thenCompose(val -> new ContentDigest(val, alg).hex())
            .thenApply(hex -> json.add(alg.name().toLowerCase(Locale.US), hex));
    }

    /**
     * Get info index json from uploaded package.
     * @param name Package name
     * @param key Package input stream
     * @return JsonObjectBuilder with package info as completion action
     */
    private CompletionStage<JsonObjectBuilder> infoJson(final String name, final Key key) {
        return this.asto.value(key).thenCompose(
            val -> new ContentAsStream<JsonObjectBuilder>(val).process(
                input -> {
                    final InfoIndex info;
                    if (name.endsWith("conda")) {
                        info = new InfoIndex.Conda(input);
                    } else {
                        info = new InfoIndex.TarBz(input);
                    }
                    return Json.createObjectBuilder(new UncheckedIOScalar<>(info::json).value())
                        .add("size", val.size().get());
                }
            )
        );
    }

    /**
     * Obtain file part from multipart body.
     * @param headers Request headers
     * @param body Request body
     * @return File part as Publisher of ByteBuffer
     * @todo #32:30min Obtain Content-Length from another multipart body part and return from this
     *  method Content built with length. Content-Length of the file is provided in format:
     *  --multipart boundary
     *  Content-Disposition: form-data; name="Content-Length"
     *  //empty line
     *  2123
     *  --multipart boundary
     *  ...
     *  Multipart body format can be also checked in logs of
     *  CondaSliceITCase#canPublishWithCondaBuild() test method.
     */
    private static Publisher<ByteBuffer> filePart(final Headers headers,
        final Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(
            new RqMultipart(headers, body).inspect(
                (part, inspector) -> {
                    if (new ContentDisposition(part.headers()).fieldName().equals("file")) {
                        inspector.accept(part);
                    } else {
                        inspector.ignore(part);
                    }
                    final CompletableFuture<Void> res = new CompletableFuture<>();
                    res.complete(null);
                    return res;
                }
            )
        ).flatMap(part -> part);
    }
}
