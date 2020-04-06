package org.mule.weave.v2.module.raml

import java.util.concurrent.CompletableFuture

import amf.Core
import amf.MessageStyles
import amf.RamlProfile
import amf.client.environment.Environment
import amf.client.model.StrField
import amf.client.model.document.Document
import amf.client.model.domain.AnyShape
import amf.client.model.domain.ArrayShape
import amf.client.model.domain.EndPoint
import amf.client.model.domain.FileShape
import amf.client.model.domain.NilShape
import amf.client.model.domain.NodeShape
import amf.client.model.domain.Parameter
import amf.client.model.domain.Payload
import amf.client.model.domain.RecursiveShape
import amf.client.model.domain.ScalarShape
import amf.client.model.domain.SecurityRequirement
import amf.client.model.domain.Shape
import amf.client.model.domain.TupleShape
import amf.client.model.domain.UnionShape
import amf.client.model.domain.WebApi
import amf.client.parse.Raml10Parser
import amf.client.remote.Content
import amf.client.resource.ClientResourceLoader
import amf.core.model.DataType
import amf.plugins.document.webapi.validation.PayloadValidatorPlugin
import org.mule.weave.v2.grammar.AsOpId
import org.mule.weave.v2.grammar.ValueSelectorOpId
import org.mule.weave.v2.module.raml.RamlModuleLoader.BODY
import org.mule.weave.v2.module.raml.RamlModuleLoader.HEADERS
import org.mule.weave.v2.module.raml.RamlModuleLoader.HOST_PARAM
import org.mule.weave.v2.module.raml.RamlModuleLoader.HOST_VAL
import org.mule.weave.v2.module.raml.RamlModuleLoader.HttpClientResponse
import org.mule.weave.v2.module.raml.RamlModuleLoader.OPERATION_PARAM
import org.mule.weave.v2.module.raml.RamlModuleLoader.QUERY_PARAM
import org.mule.weave.v2.module.raml.RamlModuleLoader.URI_PARAM
import org.mule.weave.v2.parser.CanonicalPhaseCategory
import org.mule.weave.v2.parser.Message
import org.mule.weave.v2.parser.SafeStringBasedParserInput
import org.mule.weave.v2.parser.annotation.InfixNotationFunctionCallAnnotation
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.LocationInjectorHelper
import org.mule.weave.v2.parser.ast.functions.DoBlockNode
import org.mule.weave.v2.parser.ast.functions.FunctionCallNode
import org.mule.weave.v2.parser.ast.functions.FunctionCallParametersNode
import org.mule.weave.v2.parser.ast.functions.FunctionNode
import org.mule.weave.v2.parser.ast.functions.FunctionParameter
import org.mule.weave.v2.parser.ast.functions.FunctionParameters
import org.mule.weave.v2.parser.ast.header.HeaderNode
import org.mule.weave.v2.parser.ast.header.directives.DirectiveNode
import org.mule.weave.v2.parser.ast.header.directives.FunctionDirectiveNode
import org.mule.weave.v2.parser.ast.header.directives.ImportDirective
import org.mule.weave.v2.parser.ast.header.directives.ImportedElement
import org.mule.weave.v2.parser.ast.header.directives.ImportedElements
import org.mule.weave.v2.parser.ast.header.directives.TypeDirective
import org.mule.weave.v2.parser.ast.header.directives.VarDirective
import org.mule.weave.v2.parser.ast.header.directives.VersionDirective
import org.mule.weave.v2.parser.ast.module.ModuleNode
import org.mule.weave.v2.parser.ast.operators.BinaryOpNode
import org.mule.weave.v2.parser.ast.selectors.NullSafeNode
import org.mule.weave.v2.parser.ast.selectors.NullUnSafeNode
import org.mule.weave.v2.parser.ast.structure.KeyNode
import org.mule.weave.v2.parser.ast.structure.KeyValuePairNode
import org.mule.weave.v2.parser.ast.structure.NameNode
import org.mule.weave.v2.parser.ast.structure.ObjectNode
import org.mule.weave.v2.parser.ast.structure.QuotedStringNode
import org.mule.weave.v2.parser.ast.structure.StringNode
import org.mule.weave.v2.parser.ast.types.KeyTypeNode
import org.mule.weave.v2.parser.ast.types.KeyValueTypeNode
import org.mule.weave.v2.parser.ast.types.NameTypeNode
import org.mule.weave.v2.parser.ast.types.ObjectTypeNode
import org.mule.weave.v2.parser.ast.types.TypeReferenceNode
import org.mule.weave.v2.parser.ast.types.UnionTypeNode
import org.mule.weave.v2.parser.ast.types.WeaveTypeNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.ast.variables.NameIdentifier.SEPARATOR
import org.mule.weave.v2.parser.ast.variables.VariableReferenceNode
import org.mule.weave.v2.parser.location.ParserPosition
import org.mule.weave.v2.parser.location.WeaveLocation
import org.mule.weave.v2.parser.phase.FailureResult
import org.mule.weave.v2.parser.phase.ModuleLoader
import org.mule.weave.v2.parser.phase.ParsingContentInput
import org.mule.weave.v2.parser.phase.ParsingContext
import org.mule.weave.v2.parser.phase.ParsingResult
import org.mule.weave.v2.parser.phase.PhaseResult
import org.mule.weave.v2.parser.phase.SuccessResult
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class DataWeaveResourceLoader(resourceResolver: WeaveResourceResolver) extends ClientResourceLoader {
  override def fetch(resource: String): CompletableFuture[Content] = {
    CompletableFuture.supplyAsync(() => {
      val maybeResource = ClassLoaderWeaveResourceResolver().lookupResource(resource)
      maybeResource.map((r) => new Content(r.content, r.url())).getOrElse(throw new RuntimeException(s"Unable to resolve ${resource}"))
    })
  }
}

/**
  * This module handles the load for any
  */
class RamlModuleLoader extends ModuleLoader {

  amf.plugins.document.WebApi.register()
  amf.plugins.document.Vocabularies.register()
  amf.plugins.features.AMFValidation.register()
  amf.Core.registerPlugin(PayloadValidatorPlugin)
  amf.Core.init.get

  override def loadModule(nameIdentifier: NameIdentifier, moduleContext: ParsingContext): Option[PhaseResult[ParsingResult[ModuleNode]]] = {
    val resourceResolver = ClassLoaderWeaveResourceResolver()
    val ramlPath = nameIdentifier.name.replaceAll(SEPARATOR, "/") + ".raml"
    val maybeResource = resourceResolver.lookupResource(ramlPath)
    maybeResource
      .map((resource) => {
        val ramlParser = new Raml10Parser(Environment(new DataWeaveResourceLoader(resourceResolver)))
        val raml = resource.content()
        val baseUnit = ramlParser.parseStringAsync(ramlPath, raml).get()
        val value = Core.validate(baseUnit, RamlProfile, MessageStyles.RAML).get()
        val messages = value.results.asScala
        if (messages.nonEmpty) {
          messages.map((message) => {
            val position = message.position
            val content = SafeStringBasedParserInput(raml)
            moduleContext.messageCollector.error(Message(message.message, CanonicalPhaseCategory), WeaveLocation(ParserPosition(position.start.line, content), ParserPosition(position.end.line, content), nameIdentifier))
          })
          FailureResult(moduleContext)
        } else {
          val input = ParsingContentInput(WeaveResource(resource.url(), ""), nameIdentifier, SafeStringBasedParserInput(""))
          val withPosition = LocationInjectorHelper.injectPosition(buildModule(nameIdentifier, baseUnit.asInstanceOf[Document]))
          val result: ParsingResult[ModuleNode] = ParsingResult(input, withPosition)
          SuccessResult(result, moduleContext)
        }
      })
  }

  def asObjectSecurityParameter(security: Seq[SecurityRequirement]): Option[KeyValueTypeNode] = {
    val authsTypes = security.flatMap((sec) => {
      sec.schemes.asScala.flatMap((scheme) => {
        val theScheme = Option(scheme.scheme)
        theScheme.flatMap((s) => {
          val schemeType = s.`type`.value().toLowerCase
          if (schemeType.contains("oauth")) {
            Some("OAuth")
          } else if (schemeType.contains("basic")) {
            Some("BasicAuth")
          } else {
            None
          }
        })
      })
    }).distinct
    val nodes: Seq[WeaveTypeNode] = authsTypes.map((typeName) => TypeReferenceNode(NameIdentifier(s"dw::io::http::Client::${typeName}")))
    if (nodes.isEmpty) {
      None
    } else {
      val node: WeaveTypeNode = nodes.reduce((v, acc) => UnionTypeNode(v, acc))
      Some(KeyValueTypeNode(KeyTypeNode(NameTypeNode(Some("auth"))), node, repeated = false, optional = false))
    }
  }

  def buildModule(nameIdentifier: NameIdentifier, unresovledDocument: Document): ModuleNode = {

    val shapes = unresovledDocument.declares.asScala.collect({ case s: Shape => s })

    val importHttpClient = ImportDirective(ImportedElement(NameIdentifier("dw::io::http::Client")), ImportedElements(Seq(ImportedElement(NameIdentifier("*")))))
    val importTypesClient = ImportDirective(ImportedElement(NameIdentifier("dw::io::http::Types")), ImportedElements(Seq(ImportedElement(NameIdentifier("*")))))
    val document: Document = Core.resolver("RAML").resolve(unresovledDocument).asInstanceOf[Document]
    val webApi = document.encodes.asInstanceOf[WebApi]
    val hostVarDirective = VarDirective(NameIdentifier(HOST_VAL), QuotedStringNode(webApi.servers.get(0).url.value()))
    val endPoints = webApi.endPoints.asScala

    val globalTypes = ArrayBuffer[String]()

    val typeDirectives: Seq[TypeDirective] = shapes.map((shape) => {
      val typeDirective = TypeDirective(NameIdentifier(shape.name.value()), None, asTypeNode(shape, globalTypes))
      globalTypes.+=(shape.name.value())
      typeDirective
    })

    val endpointFields = endPoints.flatMap((endpoint) => {
      val keyNode = KeyNode(endpoint.path.value())

      val operations = endpoint.operations.asScala.map((operation) => {
        val operationName = KeyNode(operation.method.value())
        val request = Option(operation.request)
        val uriParamsList = endpoint.parameters.asScala
        val uriParams = asObjectTypeParameter(if (uriParamsList.isEmpty) None else Some(uriParamsList), URI_PARAM, globalTypes)
        val headerParam: Option[KeyValueTypeNode] = asObjectTypeParameter(request.map(_.headers.asScala), HEADERS, globalTypes)
        val securityParam = asObjectSecurityParameter(operation.security.asScala)
        val queryParameters = asObjectTypeParameter(request.map(_.queryParameters.asScala), QUERY_PARAM, globalTypes)
        val payload = request.flatMap((request) => {
          if (request.payloads.size() >= 1) {
            Some(request.payloads.get(0))
          } else {
            None
          }
        })

        val body = payload.map(_.schema).map(asTypeNode(_, globalTypes))

        val bodyParam = body.map((bodyType) => KeyValueTypeNode(KeyTypeNode(NameTypeNode(Some(BODY))), bodyType, repeated = false, optional = false))

        val responses: Seq[WeaveTypeNode] = operation.responses.asScala.map((response) => {
          val headerProperties = mapToKeyValuePair(response.headers.asScala, globalTypes)
          val headersType = if (headerProperties.isEmpty) {
            defaultHeaderType
          } else {
            ObjectTypeNode(headerProperties)
          }
          val bodyType = response.payloads.asScala.headOption.map((payload) => {
            asTypeNode(payload.schema, globalTypes)
          }).getOrElse(anyType)
          TypeReferenceNode(NameIdentifier(HttpClientResponse), Some(Seq(bodyType, headersType)))
        })

        val responseType = if (responses.isEmpty) {
          TypeReferenceNode(NameIdentifier(HttpClientResponse), Some(Seq(anyType, defaultHeaderType)))
        } else {
          responses.reduce(UnionTypeNode)
        }

        val directiveNodes = ArrayBuffer[DirectiveNode]()

        val requestObject = ArrayBuffer[KeyValuePairNode]()

        if (bodyParam.isDefined) {
          directiveNodes.+=(VarDirective(NameIdentifier(BODY), select(VariableReferenceNode(OPERATION_PARAM), BODY)))
          requestObject.+=(KeyValuePairNode(KeyNode(BODY), VariableReferenceNode(BODY)))
        }

        if (payload.isDefined || headerParam.isDefined || securityParam.isDefined) {
          val headerParts = ArrayBuffer[AstNode]()
          if (payload.isDefined) {
            headerParts += createContentTypeNode(payload)
          }
          if (headerParam.isDefined) {
            directiveNodes.+=(VarDirective(NameIdentifier(HEADERS), select(VariableReferenceNode(OPERATION_PARAM), HEADERS)))
            headerParts += VariableReferenceNode(HEADERS)
          }

          if (securityParam.isDefined) {
            directiveNodes.+=(VarDirective(NameIdentifier(RamlModuleLoader.AUTH_VAR), select(VariableReferenceNode(OPERATION_PARAM), RamlModuleLoader.AUTH_FIELD)))
            headerParts += FunctionCallNode(VariableReferenceNode("resolveAuthorizationHeader"), FunctionCallParametersNode(Seq(VariableReferenceNode(RamlModuleLoader.AUTH_VAR))))
          }

          val headersNode: AstNode = headerParts.reduce((acc, v) => {
            createAppendNode(acc, v)
          })
          requestObject.+=(KeyValuePairNode(KeyNode(HEADERS), headersNode))
        }

        var urlToCall = createAppendNode(VariableReferenceNode(NameIdentifier(HOST_PARAM)), QuotedStringNode(endpoint.path.value()))

        if (uriParams.nonEmpty) {
          directiveNodes.+=(VarDirective(NameIdentifier(URI_PARAM), select(VariableReferenceNode(OPERATION_PARAM), URI_PARAM)))
          urlToCall = FunctionCallNode(VariableReferenceNode(NameIdentifier("resolveTemplateWith")), FunctionCallParametersNode(Seq(urlToCall, VariableReferenceNode(URI_PARAM))))
        }

        if (queryParameters.isDefined) {
          directiveNodes.+=(VarDirective(NameIdentifier(QUERY_PARAM), select(VariableReferenceNode(OPERATION_PARAM), QUERY_PARAM)))
          val writeQueryParams = FunctionCallNode(VariableReferenceNode("write"), FunctionCallParametersNode(Seq(VariableReferenceNode(QUERY_PARAM), QuotedStringNode("application/x-www-form-urlencoded"))))
          urlToCall = createAppendNode(urlToCall, createAppendNode(QuotedStringNode("?"), writeQueryParams))
        }

        val params = Seq(QuotedStringNode(operation.method.value().toUpperCase), urlToCall, ObjectNode(requestObject))
        val requestFields = Seq(bodyParam, headerParam, queryParameters, uriParams, securityParam).flatten
        val requestParam = ObjectTypeNode(requestFields)
        val operationParameter = if (requestFields.isEmpty) {
          None
        } else {
          Some(FunctionParameter(NameIdentifier(OPERATION_PARAM), None, Some(requestParam)))
        }

        val functionCallNode = as(strictSelect(FunctionCallNode(VariableReferenceNode("request"), FunctionCallParametersNode(params)), "response"), responseType)
        val operationFunctionBody = DoBlockNode(HeaderNode(directiveNodes), functionCallNode)
        val operationValue = FunctionNode(FunctionParameters(Seq(operationParameter).flatten), operationFunctionBody, Some(responseType))
        KeyValuePairNode(operationName, operationValue)
      })
      if (operations.isEmpty) {
        None
      } else {
        Some(KeyValuePairNode(keyNode, ObjectNode(operations)))
      }
    })

    val client = ObjectNode(endpointFields)
    val baseUrlParamDeclaration = FunctionParameter(NameIdentifier(HOST_PARAM), Some(VariableReferenceNode(NameIdentifier(HOST_VAL))), Some(stringType()))

    val clientFunctionDirective = FunctionDirectiveNode(NameIdentifier("client"), FunctionNode(FunctionParameters(Seq(baseUrlParamDeclaration)), client))

    val versionDirective = new VersionDirective()
    ModuleNode(nameIdentifier, Seq(versionDirective, importHttpClient, importTypesClient) ++ typeDirectives ++ Seq(hostVarDirective, clientFunctionDirective))
  }

  private def defaultHeaderType = {
    TypeReferenceNode(NameIdentifier("Dictionary"), Some(Seq(TypeReferenceNode(NameIdentifier("String")))))
  }

  private def anyType = {
    TypeReferenceNode(NameIdentifier("Any"))
  }

  def select(astNode: AstNode, selector: String): AstNode = {
    NullSafeNode(BinaryOpNode(ValueSelectorOpId, astNode, NameNode(StringNode(selector))))
  }

  def strictSelect(astNode: AstNode, selector: String): AstNode = {
    NullUnSafeNode(BinaryOpNode(ValueSelectorOpId, astNode, NameNode(StringNode(selector))))
  }

  def as(astNode: AstNode, weaveType: WeaveTypeNode): AstNode = {
    BinaryOpNode(AsOpId, astNode, weaveType)
  }

  private def asObjectTypeParameter(headersList: Option[Seq[Parameter]], paramName: String, globalTypes: Seq[String]): Option[KeyValueTypeNode] = {
    headersList.map((parameters) => {
      mapToKeyValuePair(parameters, globalTypes)
    })
      .flatMap((headers) => {
        if (headers.isEmpty) {
          None
        } else {
          Some(KeyValueTypeNode(KeyTypeNode(NameTypeNode(Option(paramName))), ObjectTypeNode(headers), repeated = false, optional = false))
        }
      })
  }

  private def mapToKeyValuePair(parameters: Seq[Parameter], globalTypes: Seq[String]) = {
    parameters.map((header) => {
      KeyValueTypeNode(KeyTypeNode(NameTypeNode(Option(header.name.value()))), asTypeNode(header.schema, globalTypes), repeated = false, optional = !header.required.value())
    })
  }

  private def createContentTypeNode(payload: Option[Payload]) = {
    ObjectNode(Seq(KeyValuePairNode(KeyNode("Content-Type"), QuotedStringNode(payload.get.mediaType.value()))))
  }

  private def asTypeNode(schema: Shape, canBeLink: Seq[String]): WeaveTypeNode = {
    schema match {
      case s if canBeLink.contains(s.name.value()) && s.name != null => TypeReferenceNode(NameIdentifier(schema.name.value()))
      case ns: NodeShape => {
        val properties = ns.properties
        val keyValuePairNodes = properties.asScala.map((property) => {
          val propName = property.name.value()
          val range = property.range
          KeyValueTypeNode(KeyTypeNode(NameTypeNode(Option(propName))), asTypeNode(range, canBeLink), property.maxCount.value() > 1, property.minCount.value() == 0)
        })
        ObjectTypeNode(keyValuePairNodes)
      }
      case rs: RecursiveShape => {
        TypeReferenceNode(NameIdentifier(rs.name.value()))
      }
      case ns: ScalarShape => {
        val dataType = ns.dataType
        TypeReferenceNode(NameIdentifier(resolveWeaveType(dataType)))
      }
      case as: ArrayShape => {
        TypeReferenceNode(NameIdentifier("Array"), Some(Seq(asTypeNode(as.items, canBeLink))))
      }
      case _: FileShape => {
        TypeReferenceNode(NameIdentifier("Binary"))
      }
      case _: NilShape => {
        TypeReferenceNode(NameIdentifier("Null"))
      }
      case ts: TupleShape => {
        val weaveTypeNode = ts.items.asScala.map(asTypeNode(_, canBeLink)).reduce((value, acc) => UnionTypeNode(value, acc))
        TypeReferenceNode(NameIdentifier("Array"), Some(Seq(weaveTypeNode)))
      }
      case us: UnionShape => {
        val anyOf = us.anyOf
          .asScala
          .map((shape) => asTypeNode(shape, canBeLink))
        anyOf.reduce((value, acc) => UnionTypeNode(value, acc))
      }
      case _: AnyShape => {
        TypeReferenceNode(NameIdentifier("Any"))
      }
    }
  }

  private def resolveWeaveType(dataType: StrField): String = {
    val maybeString = RamlModuleLoader.SIMPLE_TYPE_MAP.get(dataType.value())
    maybeString match {
      case None => {
        println(s"Unable to resolve ${dataType.value()}")
        "Any"
      }
      case Some(value) => value
    }
  }

  private def stringType() = {
    TypeReferenceNode(NameIdentifier("String"))
  }

  private def createAppendNode(left: AstNode, right: AstNode): FunctionCallNode = {
    val append = FunctionCallNode(VariableReferenceNode(NameIdentifier("++")), FunctionCallParametersNode(Seq(left, right)))
    append.annotate(InfixNotationFunctionCallAnnotation())
    append
  }

  override def name(): Option[String] = Some("raml")

}

case class EndpointTreeNode(relativePath: String, endpoint: Option[EndPoint], children: mutable.Map[String, EndpointTreeNode] = mutable.Map(), parameters: Seq[Parameter] = Seq()) {

  def addEndpoint(path: Seq[String], endpoint: EndPoint): Unit = {
    val head = path.head
    val tail = path.tail
    if (tail.isEmpty) {
      val maybeNode = children.get(head)
      maybeNode match {
        case Some(endpointNode) => {
          children.put(head, endpointNode.copy(endpoint = Option(endpoint), parameters = endpoint.parameters.asScala))
        }
        case None => {
          children.put(head, EndpointTreeNode(head, Option(endpoint), parameters = endpoint.parameters.asScala))
        }
      }
    } else {
      children.getOrElseUpdate(head, EndpointTreeNode(head, None, parameters = endpoint.parameters.asScala)).addEndpoint(tail, endpoint)
    }
  }

  def childEndpoints(): Seq[EndpointTreeNode] = {
    children.values.toSeq
  }
}

object RamlModuleLoader {
  val HOST_PARAM = "host"
  val HOST_VAL = "defaultHost"
  val BODY = "body"
  val OPERATION_PARAM = "config"
  val URI_PARAM = "uriParams"
  val QUERY_PARAM = "queryParams"
  val AUTH_FIELD = "auth"
  val AUTH_VAR = "authVar"
  val HEADERS = "headers"
  val HttpClientResponse = "HttpClientResponse"

  val SIMPLE_TYPE_MAP = Map(
    DataType.String -> "String",
    DataType.Boolean -> "Boolean",
    DataType.Number -> "Number",
    DataType.Decimal -> "Number",
    DataType.Integer -> "Number",
    DataType.Float -> "Number",
    DataType.Long -> "Number",
    DataType.DateTime -> "DateTime",
    DataType.Time -> "Time",
    DataType.DateTimeOnly -> "LocalDateTime",
    DataType.Date -> "Date",
    DataType.AnyUri -> "Uri",
    DataType.Duration -> "Period",
    DataType.Nil -> "Null",
    DataType.File -> "Binary",
    DataType.Byte -> "Binary",
    DataType.Any -> "Any",
    DataType.Password -> "String",
    DataType.Link -> "String")

}