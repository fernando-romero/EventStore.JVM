package eventstore

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.ThrottleMode.Shaping
import akka.stream.scaladsl.{ Flow, Sink, Source }
import eventstore.EsProjectionsClient.ProjectionCreationResult._
import eventstore.EsProjectionsClient.ProjectionDeleteResult._
import eventstore.EsProjectionsClient.ProjectionMode
import eventstore.EsProjectionsClient.ProjectionMode._
import play.api.libs.json.{ JsError, JsSuccess, Json }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

/**
 * The API miss documentation so I used the C# client as a starting point
 * See : https://github.com/EventStore/EventStore/blob/release-v3.9.0/src/EventStore.ClientAPI/Projections/ProjectionsClient.cs
 */
protected[this] trait EventStoreProjectionsUrls {
  def createProjectionUrl(name: String, mode: ProjectionMode = Continuous, allowEmit: Boolean = true): String = {
    val emit = if (allowEmit) "emit=1&checkpoints=yes" else "emit=0"
    val projectionModeStr = mode match {
      case OneTime    => "onetime"
      case Transient  => "transient"
      case Continuous => "continuous"
    }
    s"/projections/$projectionModeStr?name=$name&type=JS&$emit"
  }

  def projectionBaseUrl(name: String): String = s"/projection/$name"

  def fetchProjectionStateUrl(name: String, partition: Option[String]): String =
    s"${projectionBaseUrl(name)}/state" + partition.fold("")(partition => s"?partition=$partition")

  def fetchProjectionResultUrl(name: String, partition: Option[String]): String =
    s"${projectionBaseUrl(name)}/result" + partition.fold("")(partition => s"?partition=$partition")

  def projectionCommandUrl(name: String, command: String): String =
    s"${projectionBaseUrl(name)}/command/$command"
}

object EsProjectionsClient {
  sealed trait ProjectionMode
  object ProjectionMode {
    case object Continuous extends ProjectionMode
    case object OneTime extends ProjectionMode
    case object Transient extends ProjectionMode

    def apply(modeString: String): ProjectionMode = modeString.toLowerCase match {
      case "onetime"    => OneTime
      case "transient"  => Transient
      case "continuous" => Continuous
      case other        => throw new IllegalArgumentException(s"Expected ProjectionMode tp be one of OneTime|Transient|Continuous but was $other")
    }

  }

  sealed trait ProjectionStatus
  object ProjectionStatus {
    case object Running extends ProjectionStatus
    case object Faulted extends ProjectionStatus
    case object Completed extends ProjectionStatus
    case object Stopped extends ProjectionStatus
    final case class Other(status: String) extends ProjectionStatus

    def apply(statusString: String): ProjectionStatus = statusString match {
      case status if status.startsWith("Running")   => Running
      case status if status.startsWith("Completed") => Completed
      case status if status.startsWith("Stopped")   => Stopped
      case status if status.startsWith("Faulted")   => Faulted
      case other                                    => Other(other)
    }
  }

  sealed trait ProjectionCreationResult
  object ProjectionCreationResult {
    case object ProjectionCreated extends ProjectionCreationResult
    case object ProjectionAlreadyExist extends ProjectionCreationResult
  }

  sealed trait ProjectionDeleteResult
  object ProjectionDeleteResult {
    case object ProjectionDeleted extends ProjectionDeleteResult
    case class UnableToDeleteProjection(reason: String) extends ProjectionDeleteResult
  }

}

/**
 * A client allowing to create, get the status and delete an existing projection.
 */
class EsProjectionsClient(settings: Settings = Settings.Default, system: ActorSystem) extends EventStoreProjectionsUrls {

  implicit val materializer = ActorMaterializer.create(system)

  import EsProjectionsClient._
  import materializer.executionContext

  private val connection: Flow[HttpRequest, Try[HttpResponse], NotUsed] = {
    val httpExt = Http(system)
    val uri = settings.http.uri

    val connectionPool = if (uri.scheme == "http") {
      httpExt.cachedHostConnectionPool[Unit](uri.authority.host.address(), uri.authority.port)
    } else {
      httpExt.cachedHostConnectionPoolHttps[Unit](uri.authority.host.address(), uri.authority.port)
    }

    Flow[HttpRequest]
      .map(req => (req, ()))
      .via(connectionPool)
      .map { case (resp, _) => resp }
  }

  private val authorization: Option[Authorization] = settings.defaultCredentials.map(credentials => Authorization(BasicHttpCredentials(credentials.login, credentials.password)))

  private val defaultHeaders = authorization.toList

  /**
   * Create the projection with the specified name and code
   * @param name the name of the projection to create
   * @param javascript the javascript code for the projection
   * @param mode the projection's mode (Either OneTime, Continuous or Transient)
   * @param allowEmit indicates if the projection is allowed to emit new events.
   * @return
   */
  def createProjection(name: String, javascript: String, mode: ProjectionMode = Continuous, allowEmit: Boolean = true): Future[ProjectionCreationResult] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = Uri(createProjectionUrl(name, mode, allowEmit)),
      headers = defaultHeaders,
      entity = HttpEntity(ContentTypes.`application/json`, javascript))

    singleRequestWithErrorHandling(request)
      .map {
        case response if response.status == StatusCodes.Created => ProjectionCreated
        case response if response.status == StatusCodes.Conflict => ProjectionAlreadyExist
        case response => throw new ProjectionException(s"Received unexpected reponse $response")
      }
      .runWith(Sink.head)
  }

  /**
   * Fetch the details for the specified projection.
   * @param name the name of the projection
   * @return the Projection details if it exist. None otherwise
   */
  def fetchProjectionDetails(name: String): Future[Option[ProjectionDetails]] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(projectionBaseUrl(name)),
      headers = defaultHeaders)

    singleRequestWithErrorHandling(request)
      .mapAsync(1) {
        case response if response.status == StatusCodes.NotFound => Future.successful(None)
        case response => Unmarshal(response).to[String].map { rawJson =>
          val json = Json.parse(rawJson)
          Json.fromJson[ProjectionDetails](json) match {
            case JsSuccess(details, _) => Some(details)
            case e: JsError =>
              val error = Json.prettyPrint(JsError.toJson(e))
              throw new ServerErrorException(s"Invalid json for ProjectionDetails : $error")
          }
        }
      }
      .runWith(Sink.head)
  }

  private[this] def fetchProjectionData(request: HttpRequest): Future[Option[String]] = {
    singleRequestWithErrorHandling(request)
      .mapAsync(1) {
        case response if response.status == StatusCodes.NotFound => Future.successful(None)
        case response => Unmarshal(response).to[String].map(Some(_))
      }
      .runWith(Sink.head)
  }

  /**
   * Fetch the projection's state
   * @param name the name of the projection
   * @param partition the name of the partition
   * @return a String that should be either empty or a valid json object with the current state.
   */
  def fetchProjectionState(name: String, partition: Option[String] = None): Future[Option[String]] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(fetchProjectionStateUrl(name, partition)),
      headers = defaultHeaders)

    fetchProjectionData(request)
  }

  /**
   * Fetch the projection's result.
   * It only works for OneTime projections as Continuous one dont provide a result.
   * @param name the name of the projection
   * @param partition the name of the partition
   * @return a String that should be either empty or a valid json object with the projection's result.
   */
  def fetchProjectionResult(name: String, partition: Option[String] = None): Future[Option[String]] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(fetchProjectionResultUrl(name, partition)),
      headers = defaultHeaders)

    fetchProjectionData(request)
  }

  private[this] def executeCommand(name: String, command: String): Future[Unit] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = Uri(projectionCommandUrl(name, command)),
      headers = defaultHeaders,
      entity = HttpEntity(ContentTypes.`application/json`, "{}"))

    singleRequestWithErrorHandling(request)
      .map {
        case response if response.status == StatusCodes.NotFound => ()
        case response if response.status == StatusCodes.OK => ()
        case response => throw new ProjectionException(s"Received unexpected reponse $response")
      }
      .runWith(Sink.head)
  }

  /**
   * Start the projection with the specified name.
   * Note that when eventstore responds to the command. It only acknowledges it.
   * To know when it is started, you should use #waitForProjectionStatus
   * @param name the name of the projection to start
   * @return a future completed when the request is completed.
   *
   */
  def startProjection(name: String): Future[Unit] = executeCommand(name, "enable")

  /**
   * Stop the projection with the specified name.
   * Note that when eventstore responds to the command. It only acknowledges it.
   * To know when it is stopped, you should use #waitForProjectionStatus
   * @param name the name of the projection to stop
   * @return a future completed when the request is completed.
   *
   */
  def stopProjection(name: String): Future[Unit] = executeCommand(name, "disable")

  /**
   * Try to delete the projection with the specified name.
   * To delete a projection. It must be stopped first (see #stopProjection)
   * @param name the name of the projection to stop
   * @return a future telling whether the action was done (@ProjectionDeleted) or if it was not able to do so (@UnableToDeleteProjection)
   */
  def deleteProjection(name: String): Future[ProjectionDeleteResult] = {
    val request = HttpRequest(
      method = HttpMethods.DELETE,
      uri = Uri(projectionBaseUrl(name)),
      headers = defaultHeaders)

    singleRequestWithErrorHandling(request)
      .mapAsync(1) {
        case response if response.status == StatusCodes.InternalServerError => Unmarshal(response.entity).to[String].map(UnableToDeleteProjection)
        case response if response.status == StatusCodes.OK => Future.successful(ProjectionDeleted)
        case response => Future.failed(new ProjectionException(s"Received unexpected reponse $response"))
      }
      .runWith(Sink.head)
  }

  private[this] def singleRequestWithErrorHandling(request: HttpRequest): Source[HttpResponse, NotUsed] = {
    Source
      .single(request)
      .via(connection)
      .map { responseTry =>
        val response = responseTry.recover {
          case ex => throw new ProjectionException(s"Failed to query eventstore on ${request.uri}", ex)
        }.get
        if (response.status == StatusCodes.Unauthorized)
          throw new AccessDeniedException("Invalid credentials ")
        else
          response
      }
  }
}
