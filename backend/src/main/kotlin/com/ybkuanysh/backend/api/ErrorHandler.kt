package com.ybkuanysh.backend.api

import com.ybkuanysh.backend.dto.ErrorResponse
import com.ybkuanysh.backend.mock.NotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException
import com.ybkuanysh.backend.weather.WeatherUnavailableException
import com.ybkuanysh.backend.ml.MlUnavailableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

class BadRequestException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ErrorHandler {

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(e: NotFoundException) = ErrorResponse(e.message ?: "Not found")

    @ExceptionHandler(BadRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: BadRequestException) = ErrorResponse(e.message ?: "Bad request")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun missingParam(e: MissingServletRequestParameterException) =
        ErrorResponse("Missing required parameter '${e.parameterName}'")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(e: MethodArgumentTypeMismatchException) =
        ErrorResponse("Invalid value '${e.value}' for parameter '${e.name}'")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        ErrorResponse("Invalid request body: check required fields and formats (dates as yyyy-MM-dd)")

    @ExceptionHandler(ResourceAccessException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun llmUnavailable(e: ResourceAccessException) =
        ErrorResponse("LLM is unavailable (is Ollama running?): ${e.message}")

    @ExceptionHandler(WeatherUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun weatherUnavailable(e: WeatherUnavailableException) = ErrorResponse(e.message ?: "Weather unavailable")

    @ExceptionHandler(MlUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun mlUnavailable(e: MlUnavailableException) = ErrorResponse(e.message ?: "ML service unavailable")
}
