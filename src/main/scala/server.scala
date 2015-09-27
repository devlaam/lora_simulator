package com.devlaam.simulate.server

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

import scala.language.postfixOps
import scala.util.{ Random, Try}
import scala.collection.immutable._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.io.{ IO, Udp , UdpConnected}
import akka.actor.{ Actor, ActorRef, Props, ActorSystem, ActorLogging, Cancellable }
import akka.event.Logging
import akka.pattern.{ ask, pipe } 
import akka.util.{ Timeout, ByteString, ByteStringBuilder}

import play.api.libs.json._
import com.devlaam.coco.JsonLib._
import com.devlaam.coco.JsStack

import com.devlaam.simulate.helpers._


object MessageBird
{ import scalaj.http.Http
  
  val http = Http("https://rest.messagebird.com/messages")

  def send(phone: String) =
  { val message = s""" {
      "recipients":[$phone],
      "originator":"TTN",
      "body":"Water in de boot!" } """

    val response  = http.postData(message)
                     .header("Authorization","AccessKey live_aOxPo5XfOXQhpslmqrfNtwbt0")
                     .header("content-type","application/json")
                     .asString
    
    if (response.code >= 300) ("SMS sent, response not 20x, but: "+response) else "SMS sent." }                 
}

object Relay
{ import scalaj.http.Http
  
  val http = Http("http://ruud.things.nonred.nl:3000")
  
  def send(json: String) =
  { val response  = http.postData(json).header("content-type","application/json").asString
      if (response.code >= 300) ("Post done, response not 20x, but: "+response) else "Message relayed."} }


case class ReadPHYPayload(data64: String, nwkSKey: Array[Byte], appSKey: Array[Byte], topFCnt: Int = 0) extends PHYPayload
{ import base64._      
  import pimps.BytesOps
  
  private def printError =
  { println("BASE ERROR")
    println("data64 = "+data64)
    val data64Ints = data64.toArray.map(_.toInt).toList
    println("data64 = "+data64Ints) }

  val dataBare        = decode64(data64).getOrElse(Array[Byte]())         
  val dataMHDR        = dataBare.take(1)  
  val dataDevAddr     = dataBare.drop(1).take(4)
  val dataFCtrl       = dataBare.drop(5).take(1)
  val dataFCnt        = dataBare.drop(6).take(2) ++ arr8(topFCnt & 0xFF) ++ arr8(topFCnt>>>8 & 0xFF )
  val dataFOpts       = dataBare.drop(8).take(dataFoptsLen)
  val dataFPort       = dataBare.drop(8+dataFoptsLen).take(1)
  val dataFRMPayload  = dataBare.drop(9+dataFoptsLen).dropRight(4)
  val dataMIC         = dataBare.takeRight(4)
  
  val payloadLen      = dataFRMPayload.size
  val blockedPayload  = dataFRMPayload.grouped(16).toArray.map(_.padTo(16,0x00.toByte)).zipWithIndex

  lazy val plainPayload   = aesCryption  
  lazy val validMsg       = dataMIC sameElements calcMic.take(4) }


/* Handle the information from one specific message from the Gateway*/
case class Upstream(socket: ActorRef, remote: InetSocketAddress) 
{ import pimps._
  val defaultKey  = Array(0x2B, 0x7E, 0x15, 0x16, 0x28, 0xAE, 0xD2, 0xA6, 0xAB, 0xF7, 0x15, 0x88, 0x09, 0xCF, 0x4F, 0x3C).map(_.toByte)
  
  def make = new Action   
  
  def decrypt(data: String) = ReadPHYPayload(data,defaultKey,defaultKey).jsonFields 
  
  class Action extends Actor with ActorLogging
  { def receive = 
    { case msg: ByteString   => 
        val printer    = context.actorSelection("/user/Printer")
        val poster     = context.actorSelection("/user/Poster")
        val header     = msg.take(4)
        val gwID       = msg.drop(4).take(8).toArray.toHex
        val crypted    = JsStack.parse(msg.drop(12).decodeString("utf8"))
        val result     = ( `!{}` |+ "Gateway"-> J(gwID) 
                                 |+ "stat"   -> (crypted | "stat")
                                 |+ "rxpk"   -> (crypted | "rxpk" |* { j => j |+ "PHYPayload"->decrypt(j|"data"|>"") } ) |> )
        val response   = header.take(3) ++ header.take(1)
        printer ! result.toPretty   
        poster  ! result   
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
  
  def receive = 
  { case Udp.Bound(local) =>
      log.debug("Listening on "+local.getPort)    
      context.become(ready(sender())) 
    case _ => log.error("Unrecognized message in Listener.receive") }

  def ready(socket: ActorRef): Receive = 
  { case Udp.Received(data, remote) => 
     log.debug("Data connection requested.")
     system.actorOf(Props(Upstream(socket,remote).make)) ! data 
    
    case Udp.Unbind                 => log.debug("GOT: Udp.Unbind");       socket ! Udp.Unbind
    case Udp.Unbound                => log.debug("GOT: Udp.Unbound");      system.shutdown
    case Listener.stop              => log.debug("GOT: Listener.stop");    socket ! Udp.Unbind
    case _                          => log.error("Unrecognized message in Listener.ready") } }
  

class Printer extends Actor with ActorLogging { def receive = { case s: String => println(s);  } }


/* Actor handling the posting of UDP to the the server of Hans.
 * Use this for relaying the data for testing purposes.*/
object Poster { def props(phone: Option[String]): Props =  Props(new Poster(phone)) }

class Poster(phone: Option[String]) extends Actor with ActorLogging
{ import scalaj.http.Http
  
  lazy val http = Http("http://dev.ibeaconlivinglab.com:1880/ruud")
  
  def post(bericht: JsStack)
  { val response  = http.postData(bericht.toPretty).header("content-type","application/json").asString.code
    if (response != 200) log.info("Post done, response not 200, but: "+response)  }

  def sms(bericht: JsStack)
  { val water = bericht | "rxpk" | 0 | "PHYPayload" | "plainJson" | "water" |> false 
    if (water && phone.isDefined) MessageBird.send(phone.head) }
  
  def receive =
  { //case json: JsStack =>
    //  val bericht = json.toPretty
    //  val response  = http.postData(bericht).header("content-type","application/json").asString.code
    //  if (response != 200) log.info("Post done, response not 200, but: "+response) 
    
    case json: JsStack =>
      //post(json)
      sms(json)   } 


}


/* Main server object. Used as a UDP listener for packet forwarder. */
object main extends App
{ import pimps._
  println("This is a Lora Server Simulator")

  (args.lift(0), args.lift(1).flatMap(_.asInt)) match 
  { case (Some(address),Some(port)) =>
      println("  Listening on address "+address) 
      println("  Listening on port "+port) 
      println("  Type exit to stop.") 
      val system  = ActorSystem("MainSystem")
      val gwc = system.actorOf(Listener.props(address,port),"GlobalListener") 
      val printer = system.actorOf(Props[Printer],"Printer") 
      val poster  = system.actorOf(Poster.props(args.lift(2)),"Poster") 
      var cnt = true
      while (cnt)
      { val text = scala.io.StdIn.readLine("> ")
        cnt = (text != "exit")
        if (!cnt) gwc ! Listener.stop } 
    case _ => println("Usuage: simserv <address> <port>")
      println(" address:  address of service where to listen (usually localhost) to for gateway data.")
      println(" port:     port on local host to listen to for gateway data: port > 1024.") 
      println(" phone:    phone number to send sms to for 'hoosjebootje' demo.") } }


/* Activate this to perfom small tests ... */
object test //extends App
{ import base64._
  import crypto._
  import pimps.BytesOps
  println("Test omgeving")
  val defaultKey  = Array(0x2B, 0x7E, 0x15, 0x16, 0x28, 0xAE, 0xD2, 0xA6, 0xAB, 0xF7, 0x15, 0x88, 0x09, 0xCF, 0x4F, 0x3C).map(_.toByte)   
  val RFC4493Key  = Array(0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c).map(_.toByte) 
  val msg1 = Array[Byte]()
  val msg2 = Array(0x6b, 0xc1, 0xbe, 0xe2, 0x2e, 0x40, 0x9f, 0x96, 0xe9, 0x3d, 0x7e, 0x11, 0x73, 0x93, 0x17, 0x2a).map(_.toByte) 
  
  val cmac1 = crypto.AES.cmac(msg1,RFC4493Key).getOrElse(Array[Byte]())
  val cmac2 = crypto.AES.cmac(msg2,RFC4493Key).getOrElse(Array[Byte]())
  
  println("cmac1 = "+cmac1.toHex)
  println("cmac2 = "+cmac2.toHex)
  
  //MessageBird.send("31651311702") 
  println("done")
  
  
}
