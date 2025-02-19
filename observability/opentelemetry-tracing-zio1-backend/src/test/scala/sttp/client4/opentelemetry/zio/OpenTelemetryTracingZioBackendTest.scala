package sttp.client4.opentelemetry.zio

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.impl.zio.{RIOMonadAsyncError, ZioTestBase}
import sttp.client4.testing.BackendStub
import sttp.client4.{GenericRequest, Backend, Response, UriContext, basicRequest}
import sttp.model.StatusCode
import zio.Task
import zio.telemetry.opentelemetry.Tracing
import scala.collection.JavaConverters._

import scala.collection.mutable

class OpenTelemetryTracingZioBackendTest extends AnyFlatSpec with Matchers with BeforeAndAfter with ZioTestBase {

  private val recordedRequests = mutable.ListBuffer[GenericRequest[_, _]]()

  private val spanExporter = InMemorySpanExporter.create()

  private val mockTracer =
    SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build().get(getClass.getName)
  private val mockTracing = runtime.unsafeRun(Tracing.managed(mockTracer).useNow)

  private val backend: Backend[Task] =
    OpenTelemetryTracingZioBackend(
      BackendStub(new RIOMonadAsyncError[Any]).whenRequestMatchesPartial {
        case r if r.uri.toString.contains("echo") =>
          recordedRequests += r
          Response.ok("")
        case r if r.uri.toString.contains("error") =>
          throw new RuntimeException("something went wrong")
      },
      mockTracing
    )

  before {
    recordedRequests.clear()
    spanExporter.reset()
  }

  "ZioTelemetryOpenTelemetryBackend" should "record spans for requests" in {
    val response = runtime.unsafeRun(basicRequest.post(uri"http://stub/echo").send(backend))
    response.code shouldBe StatusCode.Ok

    val spans = spanExporter.getFinishedSpanItems.asScala
    spans should have size 1
    spans.head.getName shouldBe "HTTP POST"
  }

  it should "propagate span" in {
    val response = runtime.unsafeRun(basicRequest.post(uri"http://stub/echo").send(backend))
    response.code shouldBe StatusCode.Ok

    val spans = spanExporter.getFinishedSpanItems.asScala
    spans should have size 1

    val spanId = spans.head.getSpanId
    val traceId = spans.head.getTraceId
    recordedRequests(0).header("traceparent") shouldBe Some(s"00-${traceId}-${spanId}-01")
  }

  it should "set span status in case of error" in {
    runtime.unsafeRunSync(basicRequest.post(uri"http://stub/error").send(backend))

    val spans = spanExporter.getFinishedSpanItems.asScala
    spans should have size 1

    spans.head.getStatus.getStatusCode shouldBe io.opentelemetry.api.trace.StatusCode.ERROR
  }

}
