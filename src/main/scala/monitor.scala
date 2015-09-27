package com.devlaam.simulate.monitor


/*
    Simulator Server to connect to multiple Lora Gateways
    
    Copyright (C) 2015  Ruud Vlaming

    This program is free software: you can redistribute it and/or modify it under the terms of the 
    GNU General Public License as published by the Free Software Foundation, either version 3 of 
    the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
    See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.import scala.util.Random

*/

//http://doc.akka.io/docs/akka/2.3.12/scala/io.html
//http://doc.akka.io/docs/akka/2.3.12/scala/io-udp.html
 
import java.io._
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.util.{ Random, Try}
import scala.collection.immutable._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.io.{ IO, Udp , UdpConnected}
import akka.actor.{ Actor, ActorRef, Props, ActorSystem, ActorLogging, Cancellable }
import akka.event.Logging
import akka.pattern.{ ask, pipe } 
import akka.util.{ Timeout, ByteString, ByteStringBuilder}

import com.devlaam.simulate.helpers._


trait Command { def action: String}
case object none                       extends Command { def action = "no action" }
case object cancel                     extends Command { def action = "cancel request" }
case class info(nr: Int)               extends Command { def action = "request system status nr="+nr }
case class open_ssh(access: String)    extends Command { def action = "open ssh connection on "+ access }
case class open_ngrok(access: String)  extends Command { def action = "open ngrok connection on "+ access }
case class close_ssh()                 extends Command { def action = "close ssh connection" }
case class close_ngrok()               extends Command { def action = "close ngrok connection" }

/* Handle all incoming connections. */
//object Monitor 
//{ object stop 
//  def props(address: String, port: Int): Props =  Props(new Listener(address,port)) }
//
//class Monitor(address: String, port: Int) extends Actor with ActorLogging
//{ import context.system
//  
//  
//  
//  
//}

class Printer extends Actor with ActorLogging { def receive = { case s: String => println(s);  } }

/* Handle the information from one specific message from the Gateway*/
case class Upstream(socket: ActorRef, remote: InetSocketAddress, command: Command) 
{ import pimps._
  
  val ext = command match
  { case open_ssh(access)     => ByteString(0x03,(8022 >>> 8) & 0xFF, 8022 & 0xFF) ++ ByteString(access)
    case close_ssh()          => ByteString(0x04) 
    case open_ngrok(access)   => ByteString(0x05,0x00,0x00) ++ ByteString(access)
    case close_ngrok()        => ByteString(0x06) 
    case info(i)  => ByteString(0x02,0x00,i & 0xFF) 
    case _        => ByteString(0x00,0x00) }
  
  def make = new Action   
  
  class Action extends Actor with ActorLogging
  { def receive = 
    { case msg: ByteString   => 
        val printer    = context.actorSelection("/user/Printer")
        //val poster     = context.actorSelection("/user/Poster")
        val header     = msg.take(4)
        val response   = header ++ ext
        if (command != none) sender ! cancel
        printer ! response.toString 
        //poster  ! result   
        socket  ! Udp.Send(response, remote)
        context.stop(self)       
     case _ => log.error("Unrecognized message in Conversation") } } }

/* Handle all incoming connections. */
object Listener 
{ object stop 
  def props(address: String, port: Int): Props =  Props(new Listener(address,port)) }

class Listener(address: String, port: Int) extends Actor with ActorLogging
{ import context.system
  
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(address, port))
  
  //var node: BaseNode = DummyNode
  
  
  def receive = 
  { case Udp.Bound(local) =>
      log.debug("Listening on "+local.getPort)    
      context.become(ready(sender(),none)) 
    case _ => log.error("Unrecognized message in Listener.receive") }

  def ready(socket: ActorRef,command: Command): Receive = 
  { case Udp.Received(data, remote) => 
      val monitorID = ByteString(1,0,0,17) 
      val responseID = ByteString(1,0,0,18) 
      val marker = data.take(4)
      val printer    = context.actorSelection("/user/Printer")
       printer ! ("current state = " + command.action)
      if (marker == monitorID) 
      { log.debug("Monitor connection requested, action: "+ command.action)
         system.actorOf(Props(Upstream(socket,remote,command).make)) ! data }
      else if (marker == responseID) 
      { log.debug("Response received")
         printer ! data.drop(4).utf8String }
      else 
      { log.debug("Unknow data requested ... ignoring") } 
    
    case Udp.Unbind                 => log.debug("GOT: Udp.Unbind");       socket ! Udp.Unbind
    case Udp.Unbound                => log.debug("GOT: Udp.Unbound");      system.shutdown
    case Listener.stop              => log.debug("GOT: Listener.stop");    socket ! Udp.Unbind
    case cmd: Command               => log.debug("GOT: action "+cmd.action)
      if      (cmd == cancel && command != none) context.become(ready(socket,none)) 
      else if (command == none)                  context.become(ready(socket,cmd)) 
      else log.debug("WARN: ignoring this action! ")
    case _                          => log.error("Unrecognized message in Listener.ready") } }


/* Main server object. Used as a UDP listener for packet forwarder. */
object main extends App
{ import pimps._
  println("This is a Lora Maintenance Server")

  (args.lift(0), args.lift(1).flatMap(_.asInt)) match 
  { case (Some(address),Some(port)) =>
      println("  Listening on address "+address) 
      println("  Listening on port "+port) 
      println("  Type info to request information from the gateway.") 
      println("  Type open_ssh <user@server> to build a ssh tunnel to the gateway") 
      println("  Type close_ssh to close the ssh tunnel") 
      println("  Type open_ngrok <authToken> to build a ngrok tunnel to the gateway") 
      println("  Type close_ngrok to close the ngrok tunnel") 
      println("  Type exit to stop.")       
      val system  = ActorSystem("MainSystem")
      val gwc = system.actorOf(Listener.props(address,port),"GlobalListener") 
      val printer = system.actorOf(Props[Printer],"Printer") 
      //val poster  = system.actorOf(Props[Poster],"Poster") 
      var cnt = true
      while (cnt)
      { val text = scala.io.StdIn.readLine("> ").trim
        if (text.startsWith("open_ssh "))  gwc ! open_ssh(text.after(' ').trim)
        if (text == "close_ssh") gwc ! close_ssh
        if (text.startsWith("open_ngrok "))  gwc ! open_ngrok(text.after(' ').trim)
        if (text == "close_ngrok") gwc ! close_ngrok
        if (text == "info")  gwc ! info(0)
        if (text == "info 1")  gwc ! info(1)
        if (text == "info 2")  gwc ! info(2)
        if (text == "cancel")  gwc ! cancel
        cnt = (text != "exit")
        if (!cnt) gwc ! Listener.stop } 
    case _ => println("Usuage: simmntr <address> <port>")
      println(" address:  address of service where to listen (usually localhost) to for gateway data.")
      println(" port:     port on local host to listen to for gateway data: port > 1024.") } }

