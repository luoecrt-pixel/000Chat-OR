package com.example.openaichat

import java.io.IOException

/** Explicit refusal by the shared OpenRouter HTTP/SSE decoder; never a truncated success. */
class ResponsesSseLimitException(message: String) : IOException(message)
