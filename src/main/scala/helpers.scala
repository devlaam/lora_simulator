package com.devlaam.simulate.helpers


/*
    General Scala helper Libraries.
    
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

import com.devlaam.coco.JsonLib._
import com.devlaam.coco.JsStack

object pimps 
{ implicit class StringOps(val s: String) extends AnyVal
  { import scala.util.control.Exception._

    def asInt = catching(classOf[NumberFormatException]) opt s.toInt
    def asLong = catching(classOf[NumberFormatException]) opt s.toLong

    def asBool =
     { val lowS = s.toLowerCase
       val resT = (lowS=="true")  || (lowS=="yes") || (lowS=="on")  || (lowS=="in")
       val resF = (lowS=="false") || (lowS=="no")  || (lowS=="off") || (lowS=="out")
       if (resT) Some(true) else if (resF) Some(false)
       else catching(classOf[IllegalArgumentException]) opt s.toBoolean  }

    def asInt(dflt: Int): Int          = asInt.getOrElse(dflt)
    def asLong(dflt: Long): Long       = asLong.getOrElse(dflt)
    def asBool(dflt: Boolean): Boolean = asBool.getOrElse(dflt) 
    
    def splitAtFirst(c: Char): (String,String) =
    { val i = s.indexOf(c)
      if (i<0) (s,"") else (s.take(i),s.drop(i+1))  }
    
    def before(c: Char) = splitAtFirst(c)._1
    def after(c: Char) = splitAtFirst(c)._2


    def toAscii =
    { val cntr = List("^NUL","^SOH","^STX","^ETX","^EOT","^ENQ","^ACK","^BEL","^BS","^HT","^LF","^VT","^FF","^CR","^SO","^SI","^DLE","^DC1","^DC2","^DC3","^DC4","^NAK","^SYN","^ETB","^CAN","^EM","^SUB","^ESC","^FS","^GS","^RS","^US")
      val result = new StringBuilder(2*s.length);
      for (i <-0 until s.length)
      { val n = s(i).toInt
        if (n<32) result.append(cntr(n)) else result.append(s(i)) }
      result.toString } }

  implicit class BytesOps(val a: Array[Byte]) extends AnyVal
  { def toHex: String = a.map(("%02X") format _).mkString(":")
    def toLongLE = a.foldRight(0L)( (b,l) => (b & 0xFF) | (l << 8) ) 
    def toLongBE = a.foldLeft(0L)( (l,b)  => (b & 0xFF) | (l << 8) ) 
    def toText   = a.map(_.toChar).mkString
    def toAscii  = a.map( b => if (b<32 || b>127) "." else b.toChar).mkString } }


object base64
{ import scala.util.Try
  def decode64(str: String): Try[Array[Byte]]   = Try(java.util.Base64.getDecoder.decode(str))
  def encode64(array: Array[Byte]): Try[String] = Try(java.util.Base64.getEncoder.encode(array)).map(enc => new String(enc,0,enc.length,"UTF-8")); }


/* Simple crypto engine with do-it-yourself padding ... */
object crypto 
{ import scala.util.{ Try, Failure}
  import javax.crypto.spec.SecretKeySpec
  import javax.crypto.Cipher
  import main.java.AesCmac
  
  type Bytes = Array[Byte]
  
  object AES extends Engine("AES")
  object DES extends Engine("DES")
  
  trait Crypto 
  { def encrypt(dataBytes: Bytes, key: Bytes): Try[Bytes] 
    def decrypt(codeBytes: Bytes, key: Bytes): Try[Bytes] 
    def cmac(codeBytes: Bytes, key: Bytes): Try[Bytes] }

  class Engine(algorithmName: String) extends Crypto 
  { def encrypt(payload: Bytes, key: Bytes): Try[Bytes] = Try(
    { val secretKey = new SecretKeySpec(key, algorithmName)
      val encipher = Cipher.getInstance(algorithmName + "/ECB/NoPadding")
      encipher.init(Cipher.ENCRYPT_MODE, secretKey)
      encipher.doFinal(payload) })

    def decrypt(payload: Bytes, key: Bytes): Try[Bytes] = Try(
    { val secretKey = new SecretKeySpec(key, algorithmName)
      val encipher = Cipher.getInstance(algorithmName + "/ECB/NoPadding")
      encipher.init(Cipher.DECRYPT_MODE, secretKey)
      encipher.doFinal(payload) }) 
      
    def cmac(payload: Bytes, key: Bytes): Try[Bytes] = 
      if (algorithmName != "AES") 
      { Failure(throw new UnsupportedOperationException("cmac is only implemented for AES.")) }
      else Try(
      { try
        { val cmac = new AesCmac(16)
          cmac.init(new SecretKeySpec(key, algorithmName))
          cmac.updateBlock(payload)
          cmac.doFinal }
        catch { case e: Exception => e.printStackTrace(); throw e; } }) } }


trait PHYPayload
{ import pimps.BytesOps
  type Bytes = Array[Byte]

  val dataMHDR:       Bytes
  val dataDevAddr:    Bytes
  val dataFCtrl:      Bytes
  val dataFCnt:       Bytes
  val dataFOpts:      Bytes
  val dataFPort:      Bytes
  val dataFRMPayload: Bytes
  val dataMIC:        Bytes
  val nwkSKey:        Bytes 
  val appSKey:        Bytes 
  val blockedPayload: Array[(Bytes,Int)]
  val payloadLen:     Int
  def plainPayload:   Bytes
  def validMsg:       Boolean
  
  lazy val dataMType       = if (dataMHDR.isEmpty) 0 else (dataMHDR.head >>> 5) & 0x07
  lazy val dataRFU1        = if (dataMHDR.isEmpty) 0 else (dataMHDR.head >>> 2) & 0x07
  lazy val dataMajor       = if (dataMHDR.isEmpty) 0 else dataMHDR.head & 0x03
  lazy val dataADR         = testBit(dataFCtrl,7)
  lazy val dataADRAckReq   = testBit(dataFCtrl,6)
  lazy val dataACK         = testBit(dataFCtrl,5)
  lazy val dataRFU2        = testBit(dataFCtrl,4)
  lazy val dataFoptsLen    = maskBit(dataFCtrl,0x0F)
  lazy val dataPHYnoMic    = dataMHDR ++ dataDevAddr ++ dataFCtrl ++ dataFCnt.take(2) ++ dataFOpts ++ dataFPort ++ dataFRMPayload; 
  lazy val calcMic         = aesMessageIntegrity
  
  def blockCnt        = blockedPayload.size
  
  protected def maskBit(byte: Bytes, mask: Int) =  if (byte.isEmpty) 0     else  (byte.head & mask).toInt
  protected def testBit(arr: Bytes, pos: Int)   =  if (arr.isEmpty)  false else  ((arr.head & (1 << pos)) != 0)

  protected def s(n: Int, p: Int)  = ((n >>> (p*8)) & 0xFF).toByte   
  protected def arr32LE(i: Int)    = Array(s(i,0),s(i,1),s(i,2),s(i,3)) 
  protected def arr16LE(i: Int)    = Array(s(i,0),s(i,1)) 
  protected def arr8(i: Int)       = Array(i & 0xFF).map(_.toByte)  
  protected def nulls(len: Int)    = Array.fill(len)(0x00.toByte)
  
  protected def aesKey                    = if (dataFPort sameElements arr8(0)) nwkSKey else appSKey 
  protected def baseVec(i: Int, j: Int)   = arr8(i) ++ nulls(5) ++ dataDevAddr ++ dataFCnt ++ nulls(1) ++ arr8(j)
  protected def keyVec(i: Int, j: Int)    = crypto.AES.encrypt(baseVec(i,j) ,aesKey).getOrElse(nulls(16))
  protected def aesCryption               = (blockedPayload map { case (bl,i) => bl zip keyVec(1,i+1) map { case (c,k) => (c ^ k).toByte } }).flatten.take(payloadLen) 
  protected def aesMessageIntegrity       = crypto.AES.cmac(baseVec(0x49,dataPHYnoMic.size) ++ dataPHYnoMic,nwkSKey).getOrElse(nulls(16))

  protected def bytesPrint1(name: String, value: Bytes) =  println(name + " = " + value.toHex )
  protected def bytesPrint2(name: String, value: Bytes) =  println(name + " = " + value.toHex + "("+ value.toLongLE+")")
  
  def printFields
  { bytesPrint1("dataMHDR        ",dataMHDR)       
    bytesPrint2("dataDevAddr     ",dataDevAddr)     
    bytesPrint1("dataFCtrl       ",dataFCtrl)       
    bytesPrint2("dataFCnt        ",dataFCnt)        
    bytesPrint1("dataFOpts       ",dataFOpts)       
    bytesPrint1("dataFPort       ",dataFPort)       
    bytesPrint1("dataFRMPayload  ",dataFRMPayload)  
    bytesPrint1("dataMIC         ",dataMIC)  
    println(    "payload length   = "+payloadLen)
    println(    "block count      = "+blockCnt) }

  def jsonFields =
  { `!{}` |+
      "MHDR"       -> J(dataMHDR.toHex)       |+
      "MType"      -> J(dataMType)            |+
      "Major"      -> J(dataMajor)            |+
      "DevAddr"    -> J(dataDevAddr.toHex)    |+
      "FCtrl"      -> J(dataFCtrl.toHex)      |+
      "ADR"        -> J(dataADR)              |+
      "ADRAckReq"  -> J(dataADRAckReq)        |+
      "ACK"        -> J(dataACK)              |+
      "FoptsLen"   -> J(dataFoptsLen)         |+
      "FCnt"       -> J(dataFCnt.toLongLE)    |+
      "FOpts"      -> J(dataFOpts.toHex)      |+
      "FPort"      -> J(dataFPort.toLongLE)   |+
      "FRMPayload" -> J(dataFRMPayload.toHex) |+  
      "MIC"        -> J(dataMIC.toHex)        |+
      "validMsg"   -> J(validMsg)             |+
      "plainHex"   -> J(plainPayload.toHex)   |+
      "plainAscii" -> J(plainPayload.toAscii) |+
      "plainJson"  -> JsStack.parse(plainPayload.toAscii) 
      }
}

