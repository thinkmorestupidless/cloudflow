/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

// TODO: regenerate GraalVM config!
package akka.cloudflow.config

import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import akka.datap.crd.App
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import pureconfig.configurable.{ genericMapReader, genericMapWriter }
import pureconfig.{
  ConfigCursor,
  ConfigFieldMapping,
  ConfigObjectCursor,
  ConfigObjectSource,
  ConfigReader,
  ConfigSource,
  ConfigWriter
}
import pureconfig.error.{ CannotConvert, ConfigReaderFailures, ExceptionThrown, FailureReason }
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

// The order of the elements in this file matter, please make sure you go from leaf to nodes
object CloudflowConfig {

  // ──────────────────────────────────────────────────────────────────────────
  // Quantity  (custom reader/writer — not derived)
  // ──────────────────────────────────────────────────────────────────────────
  final case class Quantity(value: String)

  // from: https://github.com/kubernetes/apimachinery/blob/ae8b5f5092d37b75a20fef7531de129d21b9e0b5/pkg/api/resource/quantity.go#L45-L47
  // and: https://github.com/fabric8io/kubernetes-client/blob/1b4a4561542a98d75bf7f45cd203aae8a1db4e38/kubernetes-model-generator/kubernetes-model-core/src/main/java/io/fabric8/kubernetes/api/model/Quantity.java#L127-L173
  // TODO: remove after we bump to fabric8 that includes this fix: https://github.com/fabric8io/kubernetes-client/pull/2897
  private val validQuantityFormats =
    Seq("Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "n", "u", "m", "k", "M", "G", "T", "P", "E", "", null)

  given ConfigReader[Quantity] = ConfigReader.fromCursor[Quantity] { cur =>
    cur.asString.flatMap { str =>
      Try {
        val q = io.fabric8.kubernetes.api.model.Quantity.parse(str)
        assert { validQuantityFormats.contains(q.getFormat) }
        io.fabric8.kubernetes.api.model.Quantity.getAmountInBytes(q)
      } match {
        case Success(v) if v != null => Right(Quantity(str))
        case _ =>
          cur.failed(CannotConvert(str, "Quantity", s"is not a valid Kubernetes quantity"))
      }
    }
  }

  given ConfigWriter[Quantity] = ConfigWriter.fromFunction[Quantity] { qty =>
    ConfigValueFactory.fromAnyRef(io.fabric8.kubernetes.api.model.Quantity.parse(qty.value).toString())
  }

  // ──────────────────────────────────────────────────────────────────────────
  // EnvVar
  // ──────────────────────────────────────────────────────────────────────────
  final case class EnvVar(name: String, value: String)

  given ProductHint[EnvVar] = ProductHint[EnvVar](allowUnknownKeys = false)
  given ConfigReader[EnvVar] = deriveReader[EnvVar]
  given ConfigWriter[EnvVar] = ConfigWriter.forProduct2("name", "value")(e => (e.name, e.value))

  // ──────────────────────────────────────────────────────────────────────────
  // Requirement
  // ──────────────────────────────────────────────────────────────────────────
  final case class Requirement(memory: Option[Quantity] = None, cpu: Option[Quantity] = None)

  given ProductHint[Requirement] = ProductHint[Requirement](allowUnknownKeys = false)
  given ConfigReader[Requirement] = deriveReader[Requirement]
  given ConfigWriter[Requirement] = ConfigWriter.forProduct2("memory", "cpu")(r => (r.memory, r.cpu))

  // ──────────────────────────────────────────────────────────────────────────
  // Volumes  (sealed trait with custom reader/writer)
  // ──────────────────────────────────────────────────────────────────────────
  sealed trait Volume
  final case class SecretVolume(name: String) extends Volume
  final case class ConfigMapVolume(
      name: String,
      optional: Boolean = false,
      items: Map[String, ConfigMapVolumeKeyToPath] = Map.empty)
      extends Volume
  final case class ConfigMapVolumeKeyToPath(path: String)
  final case class PvcVolume(name: String, readOnly: Boolean = true) extends Volume

  given ProductHint[SecretVolume] = ProductHint[SecretVolume](allowUnknownKeys = false)
  given ProductHint[PvcVolume] = ProductHint[PvcVolume](allowUnknownKeys = false)
  given ProductHint[ConfigMapVolume] = ProductHint[ConfigMapVolume](allowUnknownKeys = false)

  // Explicit leaf derivation needed before using them for Volume
  given ConfigReader[ConfigMapVolumeKeyToPath] = deriveReader[ConfigMapVolumeKeyToPath]
  given ConfigWriter[ConfigMapVolumeKeyToPath] = ConfigWriter.forProduct1("path")(_.path)

  private val secretReader: ConfigReader[SecretVolume] = deriveReader[SecretVolume]
  private val pvcReader: ConfigReader[PvcVolume] = deriveReader[PvcVolume]
  private val configMapReader: ConfigReader[ConfigMapVolume] = deriveReader[ConfigMapVolume]
  private val pvcVolumeWriter: ConfigWriter[PvcVolume] =
    ConfigWriter.forProduct2("name", "read-only")(pvc => (pvc.name, pvc.readOnly))
  private val secretVolumeWriter: ConfigWriter[SecretVolume] = ConfigWriter.forProduct1("name")(_.name)
  private val configMapVolumeWriter: ConfigWriter[ConfigMapVolume] =
    ConfigWriter.forProduct3("name", "optional", "items")(cm => (cm.name, cm.optional, cm.items))

  private def extractByType(typ: String, objCur: ConfigObjectCursor): ConfigReader.Result[Volume] = typ match {
    case "secret"     => secretReader.from(objCur)
    case "pvc"        => pvcReader.from(objCur)
    case "config-map" => configMapReader.from(objCur)
    case t =>
      objCur.failed(CannotConvert(t, "Volume", s"should be secret, pvc or config-map"))
  }

  given ConfigReader[Volume] = ConfigReader.fromCursor[Volume] { cur =>
    for {
      volume <- cur.asMap
      _ <- volume.size match {
        case i if i > 1 =>
          Left(
            ConfigReaderFailures(
              cur.failureFor(ExceptionThrown(ConfigException("volume has multiple definitions, only one is allowed")))))
        case i if i == 0 =>
          Left(
            ConfigReaderFailures(
              cur.failureFor(ExceptionThrown(ConfigException("volume doesn't have a parseable definition")))))
        case _ => Right(())
      }
      (k, v) = volume.head
      body <- v.asObjectCursor
      extracted <- extractByType(k, body)
    } yield {
      extracted
    }
  }

  given ConfigWriter[Volume] = ConfigWriter.fromFunction[Volume] { volume =>
    volume match {
      case pvc: PvcVolume =>
        ConfigFactory.parseMap(Map("pvc" -> pvcVolumeWriter.to(pvc)).asJava).root()
      case secret: SecretVolume =>
        ConfigFactory.parseMap(Map("secret" -> secretVolumeWriter.to(secret)).asJava).root()
      case configMap: ConfigMapVolume =>
        ConfigFactory.parseMap(Map("config-map" -> configMapVolumeWriter.to(configMap)).asJava).root()
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // VolumeMount
  // ──────────────────────────────────────────────────────────────────────────
  final case class VolumeMount(mountPath: String = "", readOnly: Boolean = true, subPath: String = "")

  given ProductHint[VolumeMount] = ProductHint[VolumeMount](
    ConfigFieldMapping(Map("mountPath" -> "mount-path", "readOnly" -> "read-only", "subPath" -> "subPath")),
    useDefaultArgs = true,
    allowUnknownKeys = false)
  // Custom reader: subPath stays as "subPath" (not converted to "sub-path" by default kebab-case)
  given ConfigReader[VolumeMount] = ConfigReader.fromCursor[VolumeMount] { cur =>
    cur.asObjectCursor.flatMap { obj =>
      for {
        mountPath <- {
          val c = obj.atKeyOrUndefined("mount-path"); if (c.isUndefined) Right("") else ConfigReader[String].from(c)
        }
        readOnly <- {
          val c = obj.atKeyOrUndefined("read-only"); if (c.isUndefined) Right(true) else ConfigReader[Boolean].from(c)
        }
        subPath <- {
          val c = obj.atKeyOrUndefined("subPath"); if (c.isUndefined) Right("") else ConfigReader[String].from(c)
        }
      } yield VolumeMount(mountPath, readOnly, subPath)
    }
  }
  given ConfigWriter[VolumeMount] =
    ConfigWriter.forProduct3("mount-path", "read-only", "subPath")(vm => (vm.mountPath, vm.readOnly, vm.subPath))

  // ──────────────────────────────────────────────────────────────────────────
  // ContainerPortName  (custom reader/writer — not derived)
  // ──────────────────────────────────────────────────────────────────────────
  final case class ContainerPortName(value: String)

  // https://github.com/kubernetes/kubernetes/issues/50619#issuecomment-518220654
  private val containerPortNameFormat = """^[a-z0-9A-Z\-]*""".r
  private val oneLetterFormat = "^.*[a-zA-Z].*".r

  private def validatePortName(str: String) = {
    // MUST be at least 1 character and no more than 15 characters long
    if (str.size >= 1 && str.size <= 15) {
      // MUST contain only US-ASCII [ANSI.X3.4-1986] letters 'A' - 'Z' and
      // 'a' - 'z', digits '0' - '9', and hyphens ('-', ASCII 0x2D or
      // decimal 45)
      if (containerPortNameFormat.matches(str)) {
        // MUST contain at least one letter ('A' - 'Z' or 'a' - 'z')
        if (oneLetterFormat.matches(str)) {
          // MUST NOT begin or end with a hyphen
          if (!str.startsWith("-") && !str.endsWith("-")) {
            // hyphens MUST NOT be adjacent to other hyphens
            !str.contains("--")
          } else false
        } else false
      } else false
    } else false
  }

  given ConfigReader[ContainerPortName] = ConfigReader.fromCursor[ContainerPortName] { cur =>
    cur.asString.flatMap { str =>
      if (validatePortName(str)) {
        Right(ContainerPortName(str))
      } else {
        cur.failed(CannotConvert(str, "ContainerPortName", s"is not a valid Kubernetes portName"))
      }
    }
  }

  given ConfigWriter[ContainerPortName] = ConfigWriter.fromFunction[ContainerPortName] { cpn =>
    ConfigValueFactory.fromAnyRef(cpn.value)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ContainerPort
  // ──────────────────────────────────────────────────────────────────────────
  final case class ContainerPort(
      containerPort: Int,
      protocol: String = "TCP",
      name: Option[ContainerPortName] = None,
      hostIP: String = "",
      hostPort: Option[Int] = None)

  given ProductHint[ContainerPort] = ProductHint[ContainerPort](
    ConfigFieldMapping(
      Map(
        "containerPort" -> "container-port",
        "protocol" -> "protocol",
        "name" -> "name",
        "hostIP" -> "host-ip",
        "hostPort" -> "host-port")),
    useDefaultArgs = true,
    allowUnknownKeys = false)
  // Custom reader: hostIP maps to "host-ip" (default kebab would give "host-i-p")
  given ConfigReader[ContainerPort] = ConfigReader.fromCursor[ContainerPort] { cur =>
    cur.asObjectCursor.flatMap { obj =>
      for {
        containerPort <- obj.atKey("container-port").flatMap(ConfigReader[Int].from)
        protocol <- {
          val c = obj.atKeyOrUndefined("protocol"); if (c.isUndefined) Right("TCP") else ConfigReader[String].from(c)
        }
        name <- {
          val c = obj.atKeyOrUndefined("name");
          if (c.isUndefined) Right(None) else ConfigReader[Option[ContainerPortName]].from(c)
        }
        hostIP <- {
          val c = obj.atKeyOrUndefined("host-ip"); if (c.isUndefined) Right("") else ConfigReader[String].from(c)
        }
        hostPort <- {
          val c = obj.atKeyOrUndefined("host-port");
          if (c.isUndefined) Right(None) else ConfigReader[Option[Int]].from(c)
        }
      } yield ContainerPort(containerPort, protocol, name, hostIP, hostPort)
    }
  }
  given ConfigWriter[ContainerPort] =
    ConfigWriter.forProduct5("container-port", "protocol", "name", "host-ip", "host-port")(cp =>
      (cp.containerPort, cp.protocol, cp.name, cp.hostIP, cp.hostPort))

  // ──────────────────────────────────────────────────────────────────────────
  // Requirements
  // ──────────────────────────────────────────────────────────────────────────
  final case class Requirements(requests: Requirement = Requirement(), limits: Requirement = Requirement())

  given ProductHint[Requirements] = ProductHint[Requirements](allowUnknownKeys = false)
  given ConfigReader[Requirements] = deriveReader[Requirements]
  given ConfigWriter[Requirements] = ConfigWriter.forProduct2("requests", "limits")(r => (r.requests, r.limits))

  // ──────────────────────────────────────────────────────────────────────────
  // Container
  // ──────────────────────────────────────────────────────────────────────────
  final case class Container(
      env: Option[List[EnvVar]] = None,
      ports: Option[List[ContainerPort]] = None,
      resources: Requirements = Requirements(),
      volumeMounts: Map[String, VolumeMount] = Map())

  given ProductHint[Container] = ProductHint[Container](allowUnknownKeys = false)
  given ConfigReader[Container] = deriveReader[Container]
  given ConfigWriter[Container] = ConfigWriter.forProduct4("env", "ports", "resources", "volume-mounts")(c =>
    (c.env, c.ports, c.resources, c.volumeMounts))

  // ──────────────────────────────────────────────────────────────────────────
  // LabelValue  (custom reader/writer — not derived)
  // ──────────────────────────────────────────────────────────────────────────
  final case class LabelValue(value: String)

  private val labelValuePattern = """^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$""".r
  private val labelValueSingleCharFormat = "^[a-z0-9A-Z]{1}$".r

  private def validateLabelValue(value: String): Boolean = {
    val legalValue = labelValuePattern.matches(value) || labelValueSingleCharFormat.matches(value)
    (!(value.contains("{") || value.size == 0)) && legalValue
  }

  given ConfigReader[LabelValue] = ConfigReader.fromCursor[LabelValue] { cur =>
    cur.asString.flatMap { str =>
      if (validateLabelValue(str)) Right(LabelValue(str))
      else cur.failed(InvalidLabelFailure(s"$InvalidLabel value:${str}"))
    }
  }

  given ConfigWriter[LabelValue] = ConfigWriter.fromFunction[LabelValue] { label =>
    ConfigValueFactory.fromAnyRef(label.value)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // LabelKey  (custom map reader/writer)
  // ──────────────────────────────────────────────────────────────────────────
  final case class LabelKey(key: String)

  private def hasPrefix(label: String) =
    label.count(_ == '/') == 1 && !label.startsWith("/") && !label.endsWith("/")

  private val illegalLabelPrefixPattern = """^[0-9\-]""".r
  private val labelPrefixPattern = """^[a-z0-9\.]{0,252}[a-z0-9]{0,1}$""".r
  private val labelPrefixSingleCharFormat = """^[a-zA-Z]{1}$""".r

  private def validatePrefix(prefix: String): Boolean = {
    val illegalPrefix = prefix.size > 0 && illegalLabelPrefixPattern.matches(prefix)
    val legalPrefix = labelPrefixPattern.matches(prefix) || labelPrefixSingleCharFormat.matches(prefix)
    !illegalPrefix && legalPrefix
  }

  private val labelNamePattern = """^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$""".r
  private val labelNameSingleCharFormat = "^[a-z0-9A-Z]{1}$".r

  private def validateKey(key: String): Boolean = {
    val legalKey = labelNamePattern.matches(key) || labelNameSingleCharFormat.matches(key)
    (!(key.contains("{") || key.size == 0)) && legalKey
  }

  private def validKey(str: String) =
    if (hasPrefix(str)) {
      val splitted = str.split('/')
      val prefix = splitted(0)
      val key = splitted(1)
      validatePrefix(prefix) && validateKey(key)
    } else {
      validateKey(str)
    }

  given labelMapReader: ConfigReader[Map[LabelKey, LabelValue]] = genericMapReader[LabelKey, LabelValue] { str =>
    if (validKey(str)) Right(LabelKey(str))
    else Left(InvalidLabelFailure(s"$InvalidLabel key:${str}"))
  }

  given labelMapWriter: ConfigWriter[Map[LabelKey, LabelValue]] = genericMapWriter[LabelKey, LabelValue] { lkey =>
    lkey.key
  }

  // ──────────────────────────────────────────────────────────────────────────
  // AnnotationKey / AnnotationValue  (custom reader/writer)
  // ──────────────────────────────────────────────────────────────────────────
  final case class AnnotationKey(key: String)
  final case class AnnotationValue(value: String)

  given ConfigReader[AnnotationValue] = ConfigReader.fromCursor[AnnotationValue] { cur =>
    cur.asString.flatMap { str =>
      // Not clear from kubernetes docs what would be invalid as annotation value, so not validating.
      Right(AnnotationValue(str))
    }
  }

  given ConfigWriter[AnnotationValue] = ConfigWriter.fromFunction[AnnotationValue] { av =>
    ConfigValueFactory.fromAnyRef(av.value)
  }

  given annotationMapReader: ConfigReader[Map[AnnotationKey, AnnotationValue]] =
    genericMapReader[AnnotationKey, AnnotationValue] { str =>
      if (validKey(str)) Right(AnnotationKey(str))
      else Left(InvalidAnnotationFailure(s"$InvalidAnnotation key:${str}"))
    }

  given annotationMapWriter: ConfigWriter[Map[AnnotationKey, AnnotationValue]] =
    genericMapWriter[AnnotationKey, AnnotationValue] { akey => akey.key }

  // ──────────────────────────────────────────────────────────────────────────
  // Pod
  // ──────────────────────────────────────────────────────────────────────────
  final case class Pod(
      labels: Map[LabelKey, LabelValue] = Map(),
      annotations: Map[AnnotationKey, AnnotationValue] = Map(),
      volumes: Map[String, Volume] = Map(),
      containers: Map[String, Container] = Map())

  given ProductHint[Pod] = ProductHint[Pod](allowUnknownKeys = false)

  val defaultPodReader: ConfigReader[Pod] = deriveReader[Pod]

  given ConfigReader[Pod] = defaultPodReader
  given ConfigWriter[Pod] = ConfigWriter.forProduct4("labels", "annotations", "volumes", "containers")(p =>
    (p.labels, p.annotations, p.volumes, p.containers))

  // ──────────────────────────────────────────────────────────────────────────
  // Kubernetes
  // ──────────────────────────────────────────────────────────────────────────
  final case class Kubernetes(pods: Map[String, Pod] = Map())

  given ProductHint[Kubernetes] = ProductHint[Kubernetes](allowUnknownKeys = false)

  val defaultKubernetesReader: ConfigReader[Kubernetes] = deriveReader[Kubernetes]

  private def getInvalidVolumeMounts(pod: Pod, declaredVolumes: Seq[String]) = {
    val vmNames = pod.containers.values.map(_.volumeMounts.keys).flatten
    vmNames.collect {
      case vmName if !declaredVolumes.exists(_ == vmName) => vmName
    }
  }

  given ConfigReader[Kubernetes] = ConfigReader.fromCursor[Kubernetes] { (cur: ConfigCursor) =>
    defaultKubernetesReader.from(cur) match {
      case Right(kubernetes) =>
        val genericVolumes = kubernetes.pods.get("pod").map(_.volumes.keys.toSeq).getOrElse(Seq())
        val invalidMounts = kubernetes.pods.values.flatMap { pod =>
          getInvalidVolumeMounts(pod, genericVolumes ++ pod.volumes.keys)
        }
        if (invalidMounts.size > 0) {
          cur.failed(InvalidMountsFailure(s"$InvalidMounts ${invalidMounts.mkString(", ")}"))
        } else {
          val failures = kubernetes.pods.collect {
            case pod if (pod._1 == "job-manager" || pod._1 == "task-manager") && pod._2.labels.nonEmpty =>
              pod._1
          }
          if (failures.size > 0) cur.failed(PodConfigFailure(s"$LabelsNotAllowedOnPod ${failures.mkString(", ")}"))
          else Right(kubernetes)
        }
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  given ConfigWriter[Kubernetes] = ConfigWriter.forProduct1("pods")(_.pods)

  // ──────────────────────────────────────────────────────────────────────────
  // Runtime
  // ──────────────────────────────────────────────────────────────────────────
  final case class Runtime(config: Config = ConfigFactory.empty(), kubernetes: Kubernetes = Kubernetes())

  given ProductHint[Runtime] = ProductHint[Runtime](allowUnknownKeys = false)
  given ConfigReader[Runtime] = deriveReader[Runtime]
  given ConfigWriter[Runtime] = ConfigWriter.forProduct2("config", "kubernetes")(r => (r.config, r.kubernetes))

  // ──────────────────────────────────────────────────────────────────────────
  // Streamlet
  // ──────────────────────────────────────────────────────────────────────────
  final case class Streamlet(
      configParameters: Config = ConfigFactory.empty(),
      config: Config = ConfigFactory.empty(),
      kubernetes: Kubernetes = Kubernetes())

  given ProductHint[Streamlet] = ProductHint[Streamlet](allowUnknownKeys = false)

  val defaultStreamletReader: ConfigReader[Streamlet] = deriveReader[Streamlet]

  given ConfigReader[Streamlet] = ConfigReader.fromCursor[Streamlet] { (cur: ConfigCursor) =>
    defaultStreamletReader.from(cur) match {
      case Right(s)
          if s.configParameters.isEmpty &&
            s.config.isEmpty &&
            s.kubernetes.pods.isEmpty =>
        cur.failed(StreamletConfigFailure(MandatorySectionsText))
      case Right(s) => Right(s)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  given ConfigWriter[Streamlet] = ConfigWriter.forProduct3("config-parameters", "config", "kubernetes")(s =>
    (s.configParameters, s.config, s.kubernetes))

  // ──────────────────────────────────────────────────────────────────────────
  // PortMapping / PortMappings / Context / StreamletContext
  // ──────────────────────────────────────────────────────────────────────────
  final case class PortMapping(id: String, config: Config)

  given ProductHint[PortMapping] = ProductHint[PortMapping](allowUnknownKeys = false)
  given ConfigReader[PortMapping] = deriveReader[PortMapping]
  given ConfigWriter[PortMapping] = ConfigWriter.forProduct2("id", "config")(pm => (pm.id, pm.config))

  final case class PortMappings(portMappings: Map[String, PortMapping])

  given ProductHint[PortMappings] = ProductHint[PortMappings](
    ConfigFieldMapping(Map("portMappings" -> "port_mappings")),
    useDefaultArgs = true,
    allowUnknownKeys = false)
  // Custom reader: portMappings maps to "port_mappings" (underscore, not hyphen)
  given ConfigReader[PortMappings] = ConfigReader.fromCursor[PortMappings] { cur =>
    cur.asObjectCursor
      .flatMap(_.atKey("port_mappings"))
      .flatMap(ConfigReader[Map[String, PortMapping]].from)
      .map(PortMappings(_))
  }
  given ConfigWriter[PortMappings] = ConfigWriter.forProduct1("port_mappings")(_.portMappings)

  final case class Context(context: PortMappings)

  given ProductHint[Context] = ProductHint[Context](allowUnknownKeys = false)
  given ConfigReader[Context] = deriveReader[Context]
  given ConfigWriter[Context] = ConfigWriter.forProduct1("context")(_.context)

  final case class StreamletContext(streamlet: Context)

  given ProductHint[StreamletContext] = ProductHint[StreamletContext](allowUnknownKeys = false)
  given ConfigReader[StreamletContext] = deriveReader[StreamletContext]
  given ConfigWriter[StreamletContext] = ConfigWriter.forProduct1("streamlet")(_.streamlet)

  // ──────────────────────────────────────────────────────────────────────────
  // TopicConfig  (custom reader/writer that wraps the derived one)
  // ──────────────────────────────────────────────────────────────────────────
  final case class TopicConfig(
      name: Option[String] = None,
      partitions: Option[Int] = None,
      replicas: Option[Int] = None) {
    protected[CloudflowConfig] var topLevelConfig: Config = ConfigFactory.empty()
  }

  given ProductHint[TopicConfig] = ProductHint[TopicConfig](allowUnknownKeys = true)

  val defaultTopicConfigReader: ConfigReader[TopicConfig] = deriveReader[TopicConfig]

  given ConfigReader[TopicConfig] = ConfigReader.fromCursor[TopicConfig] { (cur: ConfigCursor) =>
    defaultTopicConfigReader.from(cur) match {
      case Right(v) =>
        cur.asObjectCursor.foreach { top =>
          v.topLevelConfig = top.objValue.toConfig
        }
        Right(v)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  private val defaultTopicConfigWriter: ConfigWriter[TopicConfig] =
    ConfigWriter.forProduct3("name", "partitions", "replicas")(tc => (tc.name, tc.partitions, tc.replicas))

  given ConfigWriter[TopicConfig] = ConfigWriter.fromFunction[TopicConfig] { tc =>
    defaultTopicConfigWriter.to(tc).withFallback(tc.topLevelConfig)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Topic  (custom reader/writer that wraps the derived one)
  // ──────────────────────────────────────────────────────────────────────────
  final case class Topic(
      producers: List[String] = List(),
      consumers: List[String] = List(),
      cluster: Option[String] = None,
      managed: Boolean = true,
      connectionConfig: Config = ConfigFactory.empty(),
      producerConfig: Config = ConfigFactory.empty(),
      consumerConfig: Config = ConfigFactory.empty(),
      topic: TopicConfig = TopicConfig()) {
    protected[CloudflowConfig] var topLevelConfig: Config = ConfigFactory.empty()
  }

  given ProductHint[Topic] = ProductHint[Topic](allowUnknownKeys = true)

  val defaultTopicReader: ConfigReader[Topic] = deriveReader[Topic]

  given ConfigReader[Topic] = ConfigReader.fromCursor[Topic] { (cur: ConfigCursor) =>
    defaultTopicReader.from(cur) match {
      case Right(v) =>
        cur.asObjectCursor.foreach { top =>
          v.topLevelConfig = top.objValue.toConfig
        }
        Right(v)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  private val defaultTopicWriter: ConfigWriter[Topic] = ConfigWriter.forProduct8(
    "producers",
    "consumers",
    "cluster",
    "managed",
    "connection-config",
    "producer-config",
    "consumer-config",
    "topic")(t =>
    (t.producers, t.consumers, t.cluster, t.managed, t.connectionConfig, t.producerConfig, t.consumerConfig, t.topic))

  given ConfigWriter[Topic] = ConfigWriter.fromFunction[Topic] { t =>
    defaultTopicWriter.to(t).withFallback(t.topLevelConfig)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Cloudflow / CloudflowRoot
  // ──────────────────────────────────────────────────────────────────────────
  final case class Cloudflow(
      streamlets: Map[String, Streamlet] = Map(),
      runtimes: Map[String, Runtime] = Map(),
      topics: Map[String, Topic] = Map(),
      runner: Option[StreamletContext] = None)

  given ProductHint[Cloudflow] = ProductHint[Cloudflow](allowUnknownKeys = false)
  given ConfigReader[Cloudflow] = deriveReader[Cloudflow]
  given ConfigWriter[Cloudflow] = ConfigWriter.forProduct4("streamlets", "runtimes", "topics", "runner")(c =>
    (c.streamlets, c.runtimes, c.topics, c.runner))

  final case class CloudflowRoot(cloudflow: Cloudflow = Cloudflow())

  given ProductHint[CloudflowRoot] = ProductHint[CloudflowRoot](allowUnknownKeys = true)
  given ConfigReader[CloudflowRoot] = deriveReader[CloudflowRoot]
  given ConfigWriter[CloudflowRoot] = ConfigWriter.forProduct1("cloudflow")(_.cloudflow)

  // ──────────────────────────────────────────────────────────────────────────
  // Custom errors
  // ──────────────────────────────────────────────────────────────────────────
  val MandatorySectionsText = "a streamlet should have at least one of the mandatory sections"

  private case class StreamletConfigFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val LabelsNotAllowedOnPod = "Labels can NOT be applied specifically to"

  private case class PodConfigFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidLabel = "Invalid label"

  private case class InvalidLabelFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidAnnotation = "Invalid annotation"

  private case class InvalidAnnotationFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidMounts = "Volume mounts without a corresponding declared volume"

  private case class InvalidMountsFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Public API
  // ──────────────────────────────────────────────────────────────────────────

  def loadAndValidate(config: Config): Try[CloudflowConfig.CloudflowRoot] =
    loadAndValidate(ConfigSource.fromConfig(config))

  def loadAndValidate(config: ConfigObjectSource): Try[CloudflowConfig.CloudflowRoot] =
    config.load[CloudflowConfig.CloudflowRoot] match {
      case Right(value) => Success(value)
      case Left(err)    => Failure(ConfigException(s"Configuration errors:\n${err.prettyPrint()}"))
    }

  def defaultConfig(spec: App.Spec) = {
    val defaultConfig = CloudflowRoot(Cloudflow(streamlets = spec.streamlets.map { s =>
      s.name -> Streamlet(configParameters = ConfigFactory.parseMap(
        s.descriptor.configParameters
          .map { cp =>
            cp.key -> cp.defaultValue
          }
          .toMap
          .asJava))
    }.toMap))

    defaultConfig
  }

  def defaultMountsConfig(spec: App.Spec, allowedRuntimes: List[String]) = {
    val availableRuntimes = spec.deployments.map(_.runtime).filter { r => allowedRuntimes.exists(_ == r) }.distinct
    // format: off
    val defaultMounts = CloudflowRoot(Cloudflow(
      runtimes = availableRuntimes.map { runtime =>
        runtime -> Runtime(
          kubernetes = Kubernetes(
            pods = Map("pod" -> Pod(
              volumes = Map("default" -> PvcVolume(
                name = s"cloudflow-$runtime",
                readOnly = false)),
              containers = Map("container" -> Container(
                volumeMounts = Map("default" -> VolumeMount(
                  mountPath = s"/mnt/$runtime/storage",
                  readOnly = false))))))))
      }.toMap
    ))
    // format: on

    defaultMounts
  }

  def loggingMountsConfig(spec: App.Spec, loggingConfigHash: String) = {
    val allRuntimes = spec.deployments.map(_.runtime).distinct

    def pods() = {
      Map(
        "pod" -> Pod(
          volumes = Map(s"logging-${loggingConfigHash}" -> SecretVolume(name = "logging")),
          containers = Map("container" -> Container(volumeMounts =
            Map(s"logging-${loggingConfigHash}" -> VolumeMount(mountPath = s"/opt/logging", readOnly = true))))))
    }

    val loggingMounts = CloudflowRoot(Cloudflow(runtimes = allRuntimes.map { runtime =>
      runtime -> Runtime(kubernetes = Kubernetes(pods = pods()))
    }.toMap))

    loggingMounts
  }

  def writeTopic(topic: Topic) = ConfigWriter[Topic].to(topic)

  def writeConfig(config: CloudflowRoot) = ConfigWriter[CloudflowRoot].to(config)

  def runtimeConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfig =
      config.cloudflow.streamlets.get(streamletName).map(_.config).getOrElse(ConfigFactory.empty())
    val runtimeConfig =
      config.cloudflow.runtimes.get(runtimeName).map(_.config).getOrElse(ConfigFactory.empty())
    streamletConfig.withFallback(runtimeConfig)
  }

  def podsConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfig =
      config.cloudflow.streamlets
        .get(streamletName)
        .map(ConfigWriter[Streamlet].to(_))
        .getOrElse(ConfigFactory.empty())
    val runtimeConfig =
      config.cloudflow.runtimes
        .get(runtimeName)
        .map(ConfigWriter[Runtime].to(_))
        .getOrElse(ConfigFactory.empty())

    ConfigFactory
      .empty()
      .withFallback(streamletConfig)
      .withFallback(runtimeConfig)
      .withOnlyPath("kubernetes")
  }

  def streamletConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfigParams =
      config.cloudflow.streamlets
        .get(streamletName)
        .map(_.configParameters)
        .getOrElse(ConfigFactory.empty())
        .root()

    val streamletRuntimeConfig = runtimeConfig(streamletName, runtimeName, config)
    val kubernetesConfig = podsConfig(streamletName, runtimeName, config)
    val streamletConfig = streamletRuntimeConfig.withFallback(kubernetesConfig)

    ConfigFactory
      .parseMap(Map(s"cloudflow.streamlets.$streamletName" -> streamletConfigParams).asJava)
      .withFallback(streamletConfig)
  }
}

object UnsafeCloudflowConfigLoader {

  given ConfigReader[CloudflowConfig.Pod] = CloudflowConfig.defaultPodReader
  given ConfigReader[CloudflowConfig.Kubernetes] = CloudflowConfig.defaultKubernetesReader
  given ConfigReader[CloudflowConfig.Streamlet] = CloudflowConfig.defaultStreamletReader

  // In Scala 3, pureconfig's deriveReader captures given instances at macro-expansion time
  // (compile time). The ConfigReader[CloudflowRoot] defined in CloudflowConfig was derived
  // with the strict ConfigReader[Streamlet] baked in. Re-derive here so the relaxed readers
  // above are captured instead.
  private given ConfigReader[CloudflowConfig.Cloudflow] = deriveReader[CloudflowConfig.Cloudflow]
  private given ConfigReader[CloudflowConfig.CloudflowRoot] = deriveReader[CloudflowConfig.CloudflowRoot]

  def load(config: Config): Try[CloudflowConfig.CloudflowRoot] =
    ConfigSource.fromConfig(config).load[CloudflowConfig.CloudflowRoot] match {
      case Right(value) => Success(value)
      case Left(err)    => Failure(ConfigException(s"Configuration errors:\n${err.prettyPrint()}"))
    }

  def loadPodConfig(config: Config): Try[CloudflowConfig.Kubernetes] =
    ConfigSource.fromConfig(config).load[CloudflowConfig.Streamlet] match {
      case Right(value) => Success(value.kubernetes)
      case Left(err)    => Failure(ConfigException(s"Error in pod configuration:\n${err.prettyPrint()}"))
    }

}
