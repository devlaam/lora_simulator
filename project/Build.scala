import sbt._
 
object MyBuild extends Build {
 
  lazy val root = Project("root", file(".")) dependsOn(cocoProject)
  lazy val cocoProject = RootProject(uri("git://github.com/devlaam/coco.git"))
 
}