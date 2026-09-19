package com.example

import cloudflow.streamlets.avro._
import cloudflow.streamlets.StreamletShape

import cloudflow.pekkostream._
import cloudflow.streamlets.Inlet

//TODO rename to ReportPrinter
object ReportPrinterStep1 extends PekkoStreamlet {
  // 1. Create inlets and outlets
  val inlet: Inlet = AvroInlet[Report]("report-in")

  // 2. TODO Define the shape of the streamlet
  override val shape: StreamletShape = StreamletShape.empty
  // 3. TODO Override createLogic to provide StreamletLogic
  override def createLogic: PekkoStreamletLogic = new PekkoStreamletLogic() { override def run(): Unit = () }
}
