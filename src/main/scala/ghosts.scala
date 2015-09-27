package com.devlaam.simulate.ghosts


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


object NumFormatBE
{ // Big Endian definition of the integers!
  def  s(n: Int, p: Int)   = ((n >>> (p*8)) & 0xFF).toByte            
  def  uint32_t(i: Int)    = ByteString(s(i,3),s(i,2),s(i,1),s(i,0))  
  def  uint24_t(i: Int)    = ByteString(s(i,2),s(i,1),s(i,0))  
  def  uint16_t(i: Int)    = ByteString(s(i,1),s(i,0))                
  def  uint8_t(i: Int)     = ByteString(s(i,0))
  def  float(f: Float)     = uint32_t(java.lang.Float.floatToIntBits(f))
  def  uint8_a(s: String)  = ByteString(s,"UTF-8")  
}

object NumFormatLE
{ // Little Endian definition of the integers!
  def  s(n: Int, p: Int)   = ((n >>> (p*8)) & 0xFF).toByte            
  def  uint32_t(i: Int)    = ByteString(s(i,0),s(i,1),s(i,2),s(i,3))  
  def  uint24_t(i: Int)    = ByteString(s(i,0),s(i,1),s(i,2))  
  def  uint16_t(i: Int)    = ByteString(s(i,0),s(i,1))                
  def  uint8_t(i: Int)     = ByteString(s(i,0))
  def  float(f: Float)     = uint32_t(java.lang.Float.floatToIntBits(f))
  def  uint8_a(s: String)  = ByteString(s,"UTF-8")  
}

case class WritePHYPayload(fPort: Int, devAddr: Int, fCnt: Int, plainPayload: Array[Byte], nwkSKey: Array[Byte], appSKey: Array[Byte]) extends PHYPayload
{ import base64._       
  import pimps.BytesOps
  
  val dataMHDR        = arr8(0x40)  
  val dataDevAddr     = arr32LE(devAddr)
  val dataFCtrl       = arr8(0x00)
  val dataFCnt        = arr32LE(fCnt)
    
  val dataFOpts       = Array[Byte]()
  val dataFPort       = arr8(fPort)
  val validMsg        = true
  
  val payloadLen      = plainPayload.size
  val blockedPayload  = plainPayload.grouped(16).toArray.map(_.padTo(16,0x00.toByte)).zipWithIndex
  
  lazy val dataFRMPayload = aesCryption
  lazy val dataMIC        = aesMessageIntegrity.take(4)
  lazy val dataBare       = dataPHYnoMic ++ dataMIC
  lazy val data64         = encode64(dataBare).getOrElse("")
}

trait LoraMotePayload 
{ import pimps.BytesOps
  import math._
  import NumFormatBE._
  
  val i: Int
  private val rLat: Double = 20 / 10 
  private val rLon: Double = 20 / 6
  
  private val phi = (45 * i) % 360
    
  private val homeLat   = 52.690324
  private val homeLon   = 4.700838
  private val km        = 0.009  
  private val scaleLat  = 93206.7
  private val scaleLon  = 46603.4
    
  private val ledState: Int     =   i % 2
  private val pressure: Int     =   10000 + round(200 * sin(i.toDouble/10)).toInt  // (Pascal / 10 )
  private val temperature: Int  =   2100  + round(400 * sin(i.toDouble/20)).toInt  //  2.712  (grad C * 100 )
  private val altitudeBar: Int  =   100                                            // ( centimeter )
  private val batteryLevel: Int =   (254 - i) % 254 + 1                            // ( 1..254
  private val latitude: Int     =   round((homeLat + rLat*km*sin(toRadians(phi)))*scaleLat).toInt  
  private val longitude: Int    =   round((homeLon + rLon*km*cos(toRadians(phi)))*scaleLon).toInt  
  private val altitudeGps: Int  =   1                                             // meter

  val fPort = 0x02
  val plainPayload  = 
    uint8_t(ledState) ++ 
    uint16_t(pressure) ++
    uint16_t(temperature) ++
    uint16_t(altitudeBar) ++
    uint8_t(batteryLevel) ++
    uint24_t(latitude) ++
    uint24_t(longitude) ++
    uint16_t(altitudeGps) }

trait DummyPayload
{ private val content = """ {"test": true, "values": [42,43,44] } """
  val fPort = 0x01
  val plainPayload  = ByteString(content,"UTF-8") }

trait WaterPayload
{ val water = Random.nextBoolean
  val temp  = Random.nextInt(10) + 15
  val pres  = Random.nextInt(100) + 1000
  private val content = s""" {"water": $water, "temp": $temp, "pres": $pres} """
  val fPort = 0x03
  val plainPayload  = ByteString(content,"UTF-8") }


trait RadioData 
{ import NumFormatBE._
  
  private val ranFreq = 867000000 + 100000*Random.nextInt(13)
  private val ranTime = System.currentTimeMillis().toInt
  private val ranRssi = 10-Random.nextInt(130)
  private val ranSnr  = 20-Random.nextInt(40)
  
  private val freq_hz    =  uint32_t (ranFreq)   /*!> central frequency of the IF chain */
  private val if_chain   =  uint8_t  (0x01)      /*!> by which IF chain was packet received */
  private val status     =  uint8_t  (0x10)      /*!> status of the received packet */
  private val count_us   =  uint32_t (ranTime)   /*!> internal concentrator counter for timestamping, 1 microsecond resolution */
  private val rf_chain   =  uint8_t  (0x01)      /*!> through which RF chain the packet was received */
  private val modulation =  uint8_t  (0x10)      /*!> modulation used by the packet */
  private val bandwidth  =  uint8_t  (0x03)      /*!> modulation bandwidth (LoRa only) */
  private val datarate   =  uint32_t (0x40)      /*!> RX datarate of the packet (SF for LoRa) */
  private val coderate   =  uint8_t  (0x04)      /*!> error-correcting code of the packet (LoRa only) */
  private val rssi       =  float    (ranRssi)   /*!> average packet RSSI in dB */
  private val snr        =  float    (ranSnr)    /*!> average packet SNR, in dB (LoRa only) */
  private val snr_min    =  float    (-20)       /*!> minimum packet SNR, in dB (LoRa only) */
  private val snr_max    =  float    (20)        /*!> maximum packet SNR, in dB (LoRa only) */
  private val crc        =  uint16_t (0)         /*!> CRC that was received in the payload */ 
  
  val radioData = freq_hz ++ if_chain ++ status ++ count_us ++ rf_chain ++ modulation ++ bandwidth ++ datarate ++ coderate ++ rssi ++ snr ++ snr_min ++ snr_max ++ crc 
}

trait EndNode
{ val i: Int
  val address: Int
  val nwkSKey: Array[Byte]
  val appSKey: Array[Byte]
  def fPort: Int
  def plainPayload: ByteString
  def radioData: ByteString
  def cryptPayload = ByteString(WritePHYPayload(fPort,address,i,plainPayload.toArray[Byte],nwkSKey,appSKey).dataBare)
  def size = NumFormatBE.uint16_t(cryptPayload.size)             
  def result = radioData ++ size ++ cryptPayload
}

trait NodeEncryption
{ val semtechKey  = Array(0x2B, 0x7E, 0x15, 0x16, 0x28, 0xAE, 0xD2, 0xA6, 0xAB, 0xF7, 0x15, 0x88, 0x09, 0xCF, 0x4F, 0x3C).map(_.toByte)
  val nwkSKey     = semtechKey
  val appSKey     = semtechKey }

case class SimpleNode(i: Int) extends EndNode with DummyPayload with RadioData with NodeEncryption
{ val address     = 0x01E240 }

case class WaterNode(i: Int) extends EndNode with WaterPayload with RadioData with NodeEncryption
{ val address     = 0x01E220 + (i % 4) }

case class LoraMoteNode(i: Int) extends EndNode with LoraMotePayload with RadioData with NodeEncryption
{ val address     = 0x01E200 + (i % 8)  }

trait BaseNode
{ def stop: Unit
   }

object Node 
{ object tick1 
  object tick2 
  object tick3 
  object tick4 
  object stop }
 
object DummyNode extends BaseNode
{ def stop = {} }

case class Node(interval: Int, remote: InetSocketAddress, socket: ActorRef, actsys: ActorSystem) extends BaseNode
{ val doubleData = true
  
  def make = new Action
  val ghostID = ByteString(1,0,0,11) 
  
  def intidelay   =  Duration(Random.nextInt(1000*interval+50),MICROSECONDS) 
  def perioddelay =  Duration(interval+5+Random.nextInt(100),MILLISECONDS) 

  class Action extends Actor with ActorLogging
  { var cnt1 = 0
    var cnt2 = 0
    var cnt3 = 0
    val prev = collection.mutable.ArrayBuffer[ByteString]()
    
    def receive: Receive = 
    { case Node.stop => log.debug("GOT: Node.stop")  
      case Node.tick1 => 
        cnt1 = cnt1 + 1 
        val result = ghostID ++ LoraMoteNode(cnt1).result
        if (doubleData && Random.nextBoolean) prev.append(result)
        log.debug("GOT: Node.tick, sending LoraMote packet: "+cnt1)     
        socket ! Udp.Send(result,remote)
      case Node.tick2 => 
        cnt2 = cnt2 + 1 
        val result = ghostID ++ WaterNode(cnt2).result
        if (doubleData && Random.nextBoolean) prev.append(result)
        log.debug("GOT: Node.tick, sending Water packet: "+cnt2)     
        socket ! Udp.Send(result,remote)
      case Node.tick3 => 
        cnt3 = cnt3 + 1 
        val result = ghostID ++ SimpleNode(cnt3).result
        if (doubleData && Random.nextBoolean) prev.append(result)
        log.debug("GOT: Node.tick, sending simple packet: "+cnt3)     
        socket ! Udp.Send(result,remote)
      case Node.tick4 => 
         if (prev.size > 0) 
         { val result = prev(Random.nextInt(prev.size))
           socket ! Udp.Send(result,remote) }
         if (prev.size>12) prev.clear } }
      
   private val act   =  actsys.actorOf(Props(make))
   private val LoraQueue      =  actsys.scheduler.schedule(intidelay, perioddelay ,act, Node.tick1); 
   private val WaterQueue     =  actsys.scheduler.schedule(intidelay, perioddelay ,act, Node.tick2); 
   private val SimpleQueue    =  actsys.scheduler.schedule(intidelay, perioddelay ,act, Node.tick3); 
   private val DuplicateQueue =  actsys.scheduler.schedule(intidelay, perioddelay/5 ,act, Node.tick4); 
  
   def stop  = 
   { LoraQueue.cancel
     WaterQueue.cancel
     SimpleQueue.cancel
     DuplicateQueue.cancel
     act ! Node.stop }

}

/* Handle all incoming connections. */
object Listener 
{ object stop 
  def props(address: String, port: Int, interval: Int): Props =  Props(new Listener(address,port,interval)) }

class Listener(address: String, port: Int, interval: Int) extends Actor with ActorLogging
{ import context.system
  
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(address, port))
  
  var node: BaseNode = DummyNode
  
  def receive = 
  { case Udp.Bound(local) =>
      log.debug("Listening on "+local.getPort)    
      context.become(ready(sender())) 
    case _ => log.error("Unrecognized message in Listener.receive") }

  def ready(socket: ActorRef): Receive = 
  { case Udp.Received(data, remote) => 
      val ghostID = ByteString(1,0,0,11) 
      val marker = data.take(4)
      if (marker == ghostID) 
      { log.debug("Ghost connection requested.")
        node.stop;
        node = Node(interval,remote,socket,system) }
      else 
      { log.debug("Unknow data requested ... ignoring") } 
    
    case Udp.Unbind                 => log.debug("GOT: Udp.Unbind");       socket ! Udp.Unbind
    case Udp.Unbound                => log.debug("GOT: Udp.Unbound");      system.shutdown
    case Listener.stop              => log.debug("GOT: Listener.stop");    node.stop; socket ! Udp.Unbind
    case _                          => log.error("Unrecognized message in Listener.ready") } }
  

/* Main server object. Used for a node server. */ 
object main extends App
{ println("This is a Lora Node Simulator")

  (args.lift(0),args.lift(1).map(_.toInt),args.lift(2).map(_.toInt)) match 
  { case (Some(address),Some(port),Some(interval)) if (port>1024 && interval>0) =>
      println("  Listening on address "+address) 
      println("  Listening on port "+port) 
      println("  Serving nodes on "+interval+" millisecond interval." ) 
      println("  Type exit to stop.") 
      val system  = ActorSystem("MainSystem")
      val gwc = system.actorOf(Listener.props(address,port,interval),"GlobalListener")        
      var cnt = true
      while (cnt)
      { val text = scala.io.StdIn.readLine("> ")
        cnt = (text != "exit")
        if (!cnt) gwc ! Listener.stop } 
    case _ => 
      println("Usuage: simnode <address> <port> <interval>")
      println(" address:  address of service where to listen (usually localhost) to for ghost node data.")
      println(" port:     port on local host to listen to for ghost node data: port > 1024.")
      println(" interval: time between to nodes in milliseconds: interval > 0.") } }
