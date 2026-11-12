package com.undici77.aitoolset.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorMessageTest {
    @Test fun openAiAndAnthropicNestedMessage() {
        assertEquals("model 'x' not found", errorMessageOf("""{"error":{"message":"model 'x' not found","type":"invalid_request_error"}}"""))
        assertEquals("max_tokens: too big", errorMessageOf("""{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: too big"}}"""))
    }

    @Test fun plainStringError() {
        assertEquals("model \"llama\" not found, try pulling it first", errorMessageOf("""{"error":"model \"llama\" not found, try pulling it first"}"""))
    }

    @Test fun fastApiDetail() {
        assertEquals("The model `x` does not exist.", errorMessageOf("""{"detail":"The model `x` does not exist."}"""))
        assertEquals("field required; value is not a valid integer",
            errorMessageOf("""{"detail":[{"loc":["body","model"],"msg":"field required"},{"msg":"value is not a valid integer"}]}"""))
    }

    @Test fun topLevelMessage() {
        assertEquals("Unauthorized", errorMessageOf("""{"message":"Unauthorized","code":401}"""))
    }

    @Test fun notJsonOrNoMessage() {
        assertNull(errorMessageOf("<html>502 Bad Gateway</html>"))
        assertNull(errorMessageOf("""{"error":null}"""))
        assertNull(errorMessageOf("""{"foo":"bar"}"""))
        assertNull(errorMessageOf("[1,2]"))
    }

    @Test fun httpErrorFallsBackToRawBody() {
        val e = httpError(502, "  <html>Bad Gateway</html>\n")
        assertEquals("HTTP 502: <html>Bad Gateway</html>", e.message)
        assertEquals(502, e.status)
        assertEquals("HTTP 404: nope", httpError(404, """{"error":{"message":"nope"}}""").message)
    }
}
