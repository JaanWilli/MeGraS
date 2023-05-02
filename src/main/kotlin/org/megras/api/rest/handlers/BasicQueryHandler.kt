package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiBasicQuery
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiQuad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet


class BasicQueryHandler(private val quads: QuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Queries the Graph.",
        path = "/query",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiBasicQuery::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val query = try {
            ctx.bodyAsClass(ApiBasicQuery::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid query")
        }

        val results = quads.filter(
            query.s?.mapNotNull { if (it != null) QuadValue.of(it) else null },
            query.p?.mapNotNull { if (it != null) QuadValue.of(it) else null },
            query.o?.mapNotNull { if (it != null) QuadValue.of(it) else null },
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))
    }
}