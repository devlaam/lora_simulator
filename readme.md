
Lora Gateway simulator suite
============================


1. Introduction
----------------

This suite combines the former repro's: access_point (containing simsrv), ghost_node
(containing simnode) and the program simmntr. The have been renamed server, ghosts
and monitor. There function is identical, but since these applications are for 
testing only, it seems more appropriate to hold them together . 

server:
  the packet server is a primitive UDP server that can listen to the
  Lora gateway and print out the packets, or pass them on to an other
  http/udp server.

ghost:
  This node server is a primitive UDP server that can listen to the
  Lora gateway and supply it with made up data from radio nodes. 


monitor:
  This maintenance server is a primitive UDP listener to the
  Lora gateway to retrieve the system information. It can also
  be used to activate a ssh or ngrok tunnel or to the gateway 
  provided the gateway has already contains the necessary certificates.
  

This is alpha code. 

More details will follow.


2. Compile
-----------

In order to compile Lora Gateway simulator suite you need [sbt](http://www.scala-sbt.org/). Also make sure you have [Java 8 JDK](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) installed.

Go to the Lora Gateway simulator suite folder and run sbt. After startup type:

```
compile
run <arguments>
```

to stop the server type: `exit`

You can set the akka loglevel to debug for more information.


3. License
-----------

    Copyright (C) 2015  Ruud Vlaming

    This program is free software: you can redistribute it and/or modify it under the terms of the 
    GNU General Public License as published by the Free Software Foundation, either version 3 of 
    the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
    See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.import scala.util.Random

*EOF*
