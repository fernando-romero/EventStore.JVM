package eventstore

import akka.actor._
import eventstore.tcp.ConnectionActor

class EventStoreExtension(system: ActorSystem) extends Extension {

  def settings: Settings = Settings(system.settings.config)

  val actor: ActorRef = system.actorOf(ConnectionActor.props(settings), "eventstore-connection")

  val connection: EsConnection = new EsConnection(actor, system, settings)

  lazy val projectionsClient: EsProjectionsClient = new EsProjectionsClient(settings, system)
}

object EventStoreExtension extends ExtensionId[EventStoreExtension] with ExtensionIdProvider {
  def lookup() = EventStoreExtension

  def createExtension(system: ExtendedActorSystem) = new EventStoreExtension(system)
}
