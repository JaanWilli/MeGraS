package org.megras.api.rest

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.openapi.CookieAuth
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.OpenApiPluginConfiguration
import io.javalin.openapi.plugin.SecurityComponentConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import org.megras.api.rest.handlers.*
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.MutableQuadSet

object RestApi {

    private var javalin: Javalin? = null

    fun init(config: Config, objectStore: FileSystemObjectStore, quadSet: MutableQuadSet) {

        if (javalin != null) {
            stop() //stop instance in case there already is one. should not happen, just in case
        }

        val rawObjectRequestHandler = RawObjectRequestHandler(objectStore)
        val canonicalObjectRequestHandler = CanonicalObjectRequestHandler(quadSet, objectStore)
        val cachedSegmentRequestHandler = CachedSegmentRequestHandler(quadSet, objectStore)
        val canonicalSegmentRequestHandler = CanonicalSegmentRequestHandler(quadSet, objectStore)
        val aboutObjectRequestHandler = AboutObjectRequestHandler(quadSet, objectStore)
        val addFileRequestHandler = AddFileRequestHandler(quadSet, objectStore)
        val basicQueryHandler = BasicQueryHandler(quadSet)


        javalin = Javalin.create {
            it.plugins.enableCors { cors ->
                cors.add { corsPluginConfig ->
                    corsPluginConfig.anyHost()
                }
            }
            it.showJavalinBanner = false

            it.plugins.register(
                OpenApiPlugin(
                    OpenApiPluginConfiguration()
                        .withDocumentationPath("/swagger-docs")
                        .withDefinitionConfiguration { _, u ->
                            u.withOpenApiInfo { t ->
                                t.title = "MeGraS API"
                                t.version = "0.01"
                                t.description = "API for MediaGraphStore 0.01"
                            }
                        }
                )
            )

            it.plugins.register(
                SwaggerPlugin(
                    SwaggerConfiguration().apply {
                        //this.version = "4.10.3"
                        this.documentationPath = "/swagger-docs"
                        this.uiPath = "/swagger-ui"
                    }
                )
            )

        }.routes {
            get("/raw/{objectId}", rawObjectRequestHandler::get)
            get("/{objectId}", canonicalObjectRequestHandler::get)
            get("/{objectId}/about", aboutObjectRequestHandler::get)
            get("/{objectId}/c/{segmentId}", cachedSegmentRequestHandler::get)
            get("/{objectId}/segment/{segmentation}/<segmentDefinition>", canonicalSegmentRequestHandler::get)
            post("/add/file", addFileRequestHandler::post)
            post("/query", basicQueryHandler::post)
        }.exception(RestErrorStatus::class.java) { e, ctx ->
            ctx.status(e.statusCode)
            ctx.result(e.message)
        }.exception(Exception::class.java) { e, ctx ->
            e.printStackTrace()
        }

            .start(config.httpPort)
    }

    fun stop() {
        javalin?.stop()
        javalin = null
    }

}