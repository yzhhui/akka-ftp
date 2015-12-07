package com.coldcore.akkaftp.ftp
package connection

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.Date

import akka.actor.{Terminated, _}
import akka.io.{IO, Tcp}
import akka.util.{ByteString, CompactByteString}
import com.coldcore.akkaftp.ftp.command.{Reply, _}
import com.coldcore.akkaftp.ftp.core.Constants._
import com.coldcore.akkaftp.ftp.core._
import com.coldcore.akkaftp.ftp.executor.TaskExecutor
import scala.annotation.tailrec
import akka.io.Tcp.NoAck

object ControlConnector {
  def props(endpoint: InetSocketAddress, ftpstate: FtpState, executor: ActorRef): Props =
    Props(new ControlConnector(endpoint, ftpstate, executor))
}

class ControlConnector(endpoint: InetSocketAddress, ftpstate: FtpState, executor: ActorRef) extends Actor with ActorLogging {
  IO(Tcp)(context.system) ! Tcp.Bind(self, endpoint)
  log.info(s"Bound Akka FTP to ${endpoint.getHostName}:${endpoint.getPort}")

  def receive = {
    case Tcp.Connected(remote, _) => // connected
      log.debug("Remote address {} connected", remote)
      sender ! Tcp.Register(context.actorOf(ControlConnection.props(remote, sender, ftpstate, executor), name = "conn-"+ID.next))
  }

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _ => // bad
        log.debug("Closing connection (error)") // error is printed by the connection itself
        SupervisorStrategy.Stop
    }
}

object ControlConnection { //todo inactive timeout
  def props(remote: InetSocketAddress, connection: ActorRef, ftpstate: FtpState, executor: ActorRef): Props =
    Props(new ControlConnection(remote, connection, ftpstate, executor))

  case object Poison
}

class ControlConnection(remote: InetSocketAddress, connection: ActorRef, ftpstate: FtpState, executor: ActorRef) extends Actor with ActorLogging {
  import ControlConnection._
  implicit def Reply2ByteString(x: Reply): ByteString = CompactByteString(x.serialize)
  implicit def String2ByteString(x: String): ByteString = CompactByteString(x)

  case class Extracted(command: Command)
  case class Ack(command: Command) extends Tcp.Event

  class Buffer {
    val buffer = new StringBuilder
    def append(x: String) = buffer.append(x) //todo buffer overflow protection

    def extract: Option[String] = {
      val str = buffer.toString
      if (str.contains(EoL)) {
        buffer.delete(0, str.indexOf(EoL)+EoL.length)
        str.split(EoL).headOption
      } else None
    }
  }

  val session = ftpstate.sessionFactory.session(self, ftpstate)
  val buffer = new Buffer
  var executing: Option[Command] = None

  context.watch(connection)
  ftpstate.registry.addSession(session)
  session.attributes.set("connected.date", new Date())

  if (ftpstate.suspended) executor ! UnavailableCommand(session)
  else executor ! WelcomeCommand(session)

  def close() {
    context.stop(self)
    ftpstate.registry.remSession(session)
  }

  def receive = {
    case Tcp.Received(data) => // user sends data
      buffer.append(data.utf8String)
      if (executing.isEmpty)
        buffer.extract match {
          case Some(text) => // execute the command
            log.debug("{} ---> {}", remote, text)
            val command = ftpstate.commandFactory.create(text, session)
            self ! Extracted(command)
          case None => // or if the connection is poisoned
            if (session.poisoned) self ! Poison
        }

    case Extracted(command) => // execute the command now
      executing = Some(command)
      executor ! command
      session.attributes.set("lastCommand.date", new Date())

    case TaskExecutor.Executed(UnavailableCommand(_), reply) => // send 421 reply
      connection ! Tcp.Write(reply)
      log.debug("{} <--- {}", remote, reply.serialize.trim)
      connection ! Tcp.Close

    case TaskExecutor.Executed(command, reply) if reply.noop => // do not send a reply
      self ! Ack(command)

    case TaskExecutor.Executed(command, reply) => // send a reply (or replies if nested)
      @tailrec def write(command: Command, reply: Reply) {
        connection ! Tcp.Write(reply, if (reply.next.isEmpty) Ack(command) else NoAck)
        log.debug("{} <--- {}", remote, reply.serialize.trim)

        if (reply.code >= 100 && reply.code <= 199 ) { // code 1xx
          session.interruptState = true
          log.debug("{} interrupt state ON", remote)
        }
        command match {
          case x: Interrupt if x.replyClearsInterrupt && session.interruptState =>
            session.interruptState = false
            log.debug("{} interrupt state OFF", remote)
          case _ =>
        }
        if (reply.next.isDefined) write(command, reply.next.get)
      }
      write(command, reply)

    case Ack(command) => // OS acknowledges the reply was queued successfully
      if (executing == Some(command) || executing.isEmpty) {
        executing = None
        self ! Tcp.Received("") // loop to process the next command from the buffer
      }

    case Poison => // close the poisoned connection if idle
      session.poisoned = true
      if (executing.isEmpty && session.dataConnection.isEmpty)
        connection ! Tcp.Close

    case DataConnection.Success => // transfer successful
      executor ! TransferSuccessCommand(session)

    case DataConnection.Failed => // transfer failed
      executor ! TransferFailedCommand(session)

    case DataConnection.Aborted => // transfer aborted by the user
      executor ! TransferAbortedCommand(session)

    case CommonActions.SessionAliveIN => // respond or receive Kill
      sender ! CommonActions.SessionAliveOUT(session)

    case _: Tcp.ConnectionClosed => // good
      log.debug("Connection for remote address {} closed", remote)
      close()

    case Terminated(`connection`) => // bad
      log.debug("Connection for remote address {} died", remote)
      close()

    case Tcp.CommandFailed(_) => // bad
      log.debug("Connection for remote address {} failed", remote)
      close()
  }
}

//todo DataConnector PASV

object DataConnectionInitiator {
  def props(endpoint: InetSocketAddress, session: Session): Props =
    Props(new DataConnectionInitiator(endpoint, session))
}

class DataConnectionInitiator(endpoint: InetSocketAddress, session: Session) extends Actor with ActorLogging {
  IO(Tcp)(context.system) ! Tcp.Connect(endpoint)

  var remote: InetSocketAddress = _

  def fail() {
    session.ctrl ! DataConnection.Failed // notify the control connection
    DataConnection.resetSession(session)
    context.stop(self)
  }

  def receive = {
    case Tcp.Connected(remote, _) => // connected
      log.debug("Connected to remote address {}", remote)
      sender ! Tcp.Register(context.actorOf(DataConnection.props(remote, sender, session), name = "conn-"+ID.next))
      this.remote = remote

    case DataConnection.Stopped => // data connection stopped successfully
      context.stop(self)

    case Tcp.CommandFailed(_: Tcp.Connect) => // cannot connect
      log.debug("Connection to remote endpoint {} failed", endpoint.getHostName+":"+endpoint.getPort)
      fail()
  }

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _ => // bad
        log.debug("Closing connection to remote address {} (error)", remote) // error is printed by the connection itself
        fail()
        SupervisorStrategy.Stop
    }
}

object DataConnection { //todo inactive timeout
  def props(remote: InetSocketAddress, connection: ActorRef, session: Session): Props =
    Props(new DataConnection(remote, connection, session))

  case object Stopped
  case object Abort

  sealed trait ReportState
  case object Success extends ReportState
  case object Failed extends ReportState
  case object Aborted extends ReportState

  def resetSession(session: Session) {
    session.dataConnection = None
    session.dataTransferChannel = None
  }
}

class DataConnection(remote: InetSocketAddress, connection: ActorRef, session: Session) extends Actor with ActorLogging {
  import com.coldcore.akkaftp.ftp.connection.DataConnection._
  implicit def Buffer2ByteString(x: ByteBuffer): ByteString = CompactByteString(x)

  case object Write
  case object Ack extends Tcp.Event

  context.watch(connection)
  session.dataConnection = Some(self)

  val buffer = ByteBuffer.allocate(1024*8) // 8 KB buffer
  var report: Option[ReportState] = None
  var rbc: ReadableByteChannel = _
  var wbc: WritableByteChannel = _
  var transferredBytes = 0L

  val defReceive: Actor.Receive = {
    case Terminated(`connection`) => // bad
      log.debug("Connection for remote address {} died", remote)
      close()

    case Tcp.CommandFailed(_) => // bad
      log.debug("Connection for remote address {} failed", remote)
      close()
  }

  val readReceive: Actor.Receive = {
    case Tcp.Received(data) => // read data from the user
      //log.debug("{} ---> {} bytes", remote, data.size)
      val b = data.asByteBuffer
      val r = b.remaining
      val i = wbc.write(b)
      if (i != r) throw new IllegalStateException(s"Corrupted channel write: $i != $r")
      transferredBytes += i
      session.ftpstate.registry.uploadedBytes += i
      session.uploadedBytes += i

    case Abort => // abort command from the user
      report = Some(Aborted)
      connection ! Tcp.Close

    case _: Tcp.ConnectionClosed => // good
      if (report.isEmpty) report = Some(Success)
      log.debug("{} ---> {} bytes", remote, transferredBytes)
      log.debug("Connection for remote address {} closed", remote)
      close()
  }

  val writeReceive: Actor.Receive = {
    case x @ Write => // send a data chunk to the user
      buffer.clear()
      val i = rbc.read(buffer)
      buffer.flip()
      if (i != -1) {
        connection ! Tcp.Write(buffer, Ack)
        //log.debug("{} <--- {} bytes", remote, i)
        transferredBytes += i
        session.ftpstate.registry.downloadedBytes += i
        session.downloadedBytes += i
      } else {
        report = Some(Success)
        connection ! Tcp.Close
      }

    case Ack => // OS acknowledges the data chunk was queued successfully
      self ! Write // loop to send the next chunk of data

    case Abort => // abort command from the user
      report = Some(Aborted)
      connection ! Tcp.Close

    case _: Tcp.ConnectionClosed => // good
      log.debug("{} <--- {} bytes", remote, transferredBytes)
      log.debug("Connection for remote address {} closed", remote)
      close()
  }

  session.dataTransferMode.get match {
    case StorDTM | StouDTM => // write from the channel source to a client
      context.become(readReceive orElse defReceive)
      wbc = session.dataTransferChannel.get.asInstanceOf[WritableByteChannel]
    case RetrDTM | ListDTM => // read from a client into the channel dest
      context.become(writeReceive orElse defReceive)
      rbc = session.dataTransferChannel.get.asInstanceOf[ReadableByteChannel]
      self ! Write
  }

  def close() {
    session.ctrl ! report.getOrElse(Failed) // notify the control connection
    log.debug("Closing connection to remote address {}", remote)
    resetSession(session)
    context.stop(self)
    context.parent ! Stopped
  }

  def receive = defReceive
}