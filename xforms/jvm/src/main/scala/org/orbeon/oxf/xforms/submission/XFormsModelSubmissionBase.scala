/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.submission

import org.orbeon.dom._
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.http.HttpMethod
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.PathUtils.decodeSimpleQuery
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.{IndentedLogger, XPath}
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.XFormsContainingDocument
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel._
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl
import org.orbeon.oxf.xforms.event.events.{ErrorType, XFormsSubmitErrorEvent}
import org.orbeon.oxf.xforms.event.{Dispatch, ListenersTrait, XFormsEventObserver, XFormsEventTarget}
import org.orbeon.oxf.xforms.model.{BindNode, InstanceData, XFormsInstance, XFormsModel}
import org.orbeon.oxf.xml.TransformerUtils
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.saxon.om.{NodeInfo, VirtualNode}

import scala.collection.JavaConverters._
import scala.collection.mutable

abstract class XFormsModelSubmissionBase
  extends ListenersTrait
     with XFormsEventTarget
     with XFormsEventObserver {

  thisSubmission: XFormsModelSubmission ⇒

  import XFormsModelSubmissionBase._

  def getModel: XFormsModel

  protected def sendSubmitError(throwable: Throwable, submissionResult: SubmissionResult): Unit =
    sendSubmitErrorWithDefault(
      throwable,
      new XFormsSubmitErrorEvent(thisSubmission, ErrorType.XXFormsInternalError, submissionResult.getConnectionResult)
    )

  protected def sendSubmitError(throwable: Throwable, resolvedActionOrResource: String): Unit =
    sendSubmitErrorWithDefault(
      throwable,
      new XFormsSubmitErrorEvent(thisSubmission, Option(resolvedActionOrResource), ErrorType.XXFormsInternalError, 0)
    )

  private def sendSubmitErrorWithDefault(throwable: Throwable, default: ⇒ XFormsSubmitErrorEvent): Unit = {

    // After a submission, the context might have changed
    getModel.resetAndEvaluateVariables()

    // Try to get error event from exception and if not possible create default event
    val submitErrorEvent =
      (throwable collect { case se: XFormsSubmissionException ⇒ Option(se.getXFormsSubmitErrorEvent) } flatten) getOrElse default

    // Dispatch event
    submitErrorEvent.logMessage(throwable)
    Dispatch.dispatchEvent(submitErrorEvent)
  }

  protected def createDocumentToSubmit(
    currentNodeInfo   : NodeInfo,
    currentInstance   : Option[XFormsInstance],
    validate          : Boolean,
    relevanceHandling : RelevanceHandling,
    annotateWith      : Set[String])(implicit
    indentedLogger    : IndentedLogger
  ): Document = {

    // Revalidate instance
    // NOTE: We need to do this before pruning so that bind/@type works correctly. XForms 1.1 seems to say that this
    // must be done after pruning, but then it is not clear how XML Schema validation would work then.
    // Also, if validate="false" or if serialization="none", then we do not revalidate. Now whether this optimization
    // is acceptable depends on whether validate="false" only means "don't check the instance's validity" or also
    // don't even recalculate. If the latter, then this also means that type annotations won't be updated, which
    // can impact serializations that use type information, for example multipart. But in that case, here we decide
    // the optimization is worth it anyway.
    if (validate)
      currentInstance foreach (_.model.doRecalculateRevalidate())

    // Get selected nodes (re-root and handle relevance)
    val documentToSubmit =
      prepareXML(
        containingDocument,
        currentNodeInfo,
        relevanceHandling,
        Dom4jUtils.getNamespaceContext(getSubmissionElement).asScala.toMap,
        annotateWith
      )

    // Check that there are no validation errors
    // NOTE: If the instance is read-only, it can't have MIPs at the moment, and can't fail validation/requiredness,
    // so we don't go through the process at all.
    val instanceSatisfiesValidRequired =
      currentInstance.exists(_.readonly) ||
      ! validate                         ||
      isSatisfiesValidity(documentToSubmit, relevanceHandling)

    if (! instanceSatisfiesValidRequired) {
      if (indentedLogger.isDebugEnabled) {
        val documentString = TransformerUtils.tinyTreeToString(currentNodeInfo)
        indentedLogger.logDebug("", "instance document or subset thereof cannot be submitted", "document", documentString)
      }
      throw new XFormsSubmissionException(
        thisSubmission,
        "xf:submission: instance to submit does not satisfy valid and/or required model item properties.",
        "checking instance validity",
        new XFormsSubmitErrorEvent(thisSubmission, ErrorType.ValidationError, null)
      )
    }

    documentToSubmit
  }

}

object XFormsModelSubmissionBase {

  import Private._
  import RelevanceHandling._

  // Prepare XML for submission
  //
  // - re-root if `ref` points to an element other than the root element
  // - annotate with `xxf:id` if requested
  // - prune or blank non-relevant nodes if requested
  // - annotate with alerts if requested
  def prepareXML(
    xfcd              : XFormsContainingDocument,
    ref               : NodeInfo,
    relevanceHandling : RelevanceHandling,
    namespaceContext  : Map[String, String],
    annotateWith      : Set[String]
  ): Document =
    ref match {
      case virtualNode: VirtualNode ⇒

        // "A node from the instance data is selected, based on attributes on the submission
        // element. The indicated node and all nodes for which it is an ancestor are considered for
        // the remainder of the submit process. "
        val copy =
          virtualNode.getUnderlyingNode match {
            case e: Element ⇒ Dom4jUtils.createDocumentCopyParentNamespaces(e)
            case n: Node    ⇒ Dom4jUtils.createDocumentCopyElement(n.getDocument.getRootElement)
            case _          ⇒ throw new IllegalStateException
          }

        val attributeNamesForTokens =
          annotateWith.iterator map { token ⇒
            decodeSimpleQuery(token).headOption match {
              case Some((name, value)) ⇒
                name → {
                  value.trimAllToOpt map
                    (Dom4jUtils.extractTextValueQName(namespaceContext.asJava, _, true)) getOrElse
                    QName(name, XXFORMS_NAMESPACE_SHORT)
                }
              case None ⇒
                throw new IllegalArgumentException(s"invalid format for `xxf:annotate` value: `$annotateWith`")
            }
          } toMap

        // Annotate ids before pruning so that it is easier for other code (Form Runner) to infer the same ids
        attributeNamesForTokens.get("id") foreach
          (annotateWithHashes(copy, _))

        relevanceHandling match {
          case RelevanceHandling.Keep  ⇒
            attributeNamesForTokens.get("relevant") foreach
              (annotateNonRelevantElements(copy, _))
          case RelevanceHandling.Remove ⇒
            pruneNonRelevantNodes(copy)
          case RelevanceHandling.Empty ⇒
            blankNonRelevantNodes(copy)
            attributeNamesForTokens.get("relevant") foreach
              (annotateNonRelevantElements(copy, _))
        }

        annotateWithAlerts(
          xfcd             = xfcd,
          doc              = copy,
          levelsToAnnotate =
            attributeNamesForTokens.keySet collect
              LevelByName                  map { level ⇒
                level → attributeNamesForTokens(level.entryName)
            } toMap
        )

        copy

      // Submitting read-only instance backed by TinyTree (no MIPs to check)
      // TODO: What about re-rooting and annotations?
      case ref if ref.getNodeKind == org.w3c.dom.Node.ELEMENT_NODE  ⇒
        TransformerUtils.tinyTreeToDom4j(ref)
      case ref ⇒
        TransformerUtils.tinyTreeToDom4j(ref.getRoot)
    }

  def pruneNonRelevantNodes(doc: Document): Unit =
    Iterator.iterateWhileDefined(findFirstNonRelevantElementOrAttribute(doc)) foreach (_.detach())

  def blankNonRelevantNodes(doc: Document): Unit = {

    def processElement(e: Element): Unit = {

      e.attributes.asScala foreach { a ⇒
        if (! InstanceData.getInheritedRelevant(a))
          a.setValue("")
      }

      if (e.containsElement)
        e.elements.asScala foreach processElement
      else if (! InstanceData.getInheritedRelevant(e))
        e.setText("")
    }

    processElement(doc.getRootElement)

  }

  def annotateWithHashes(doc: Document, attQName: QName): Unit = {
    val wrapper = new DocumentWrapper(doc, null, XPath.GlobalConfiguration)
    var annotated = false
    doc.accept(new VisitorSupport {
      override def visit(element: Element): Unit = {
        val hash = SubmissionUtils.dataNodeHash(wrapper.wrap(element))
        element.addAttribute(attQName, hash)
        annotated = true
      }
    })
    if (annotated)
      addRootElementNamespace(doc)
  }

  // Annotate elements which have failed constraints with an xxf:error, xxf:warning or xxf:info attribute containing
  // the alert message. Only the levels passed in `annotate` are handled.
  def annotateWithAlerts(
    xfcd             : XFormsContainingDocument,
    doc              : Document,
    levelsToAnnotate : Map[ValidationLevel, QName]
  ): Unit =
    if (levelsToAnnotate.nonEmpty) {

      val elementsToAnnotate = mutable.Map[ValidationLevel, mutable.Map[Set[String], Element]]()

      // Iterate data to gather elements with failed constraints
      doc.accept(new VisitorSupport {
        override def visit(element: Element): Unit = {
          val failedValidations = BindNode.failedValidationsForAllLevelsPrioritizeRequired(element)
          for (level ← levelsToAnnotate.keys) {
            // NOTE: Annotate all levels specified. If we decide to store only one level of validation
            // in bind nodes, then we would have to change this to take the highest level only and ignore
            // the other levels.
            val failedValidationsForLevel = failedValidations.getOrElse(level, Nil)
            if (failedValidationsForLevel.nonEmpty) {
              val map = elementsToAnnotate.getOrElseUpdate(level, mutable.Map[Set[String], Element]())
              map += (failedValidationsForLevel map (_.id) toSet) → element
            }
          }
        }
      })

      if (elementsToAnnotate.nonEmpty) {
        val controls = xfcd.getControls.getCurrentControlTree.effectiveIdsToControls

        val relevantLevels = elementsToAnnotate.keySet

        def controlsIterator =
          controls.iterator collect {
            case (_, control: XFormsSingleNodeControl)
              if control.isRelevant && control.alertLevel.toList.toSet.subsetOf(relevantLevels) ⇒ control
          }

        var annotated = false

        def annotateElementIfPossible(control: XFormsSingleNodeControl) = {
          // NOTE: We check on the whole set of constraint ids. Since the control reads in all the failed
          // constraints for the level, the sets of ids must match.
          for {
            level                ← control.alertLevel
            controlAlert         ← Option(control.getAlert)
            failedValidationsIds = control.failedValidations.map(_.id).toSet
            elementsMap          ← elementsToAnnotate.get(level)
            element              ← elementsMap.get(failedValidationsIds)
            qName                ← levelsToAnnotate.get(level)
          } locally {
            // There can be an existing attribute if more than one control bind to the same element
            Option(element.attribute(qName)) match {
              case Some(existing) ⇒ existing.setValue(existing.getValue + controlAlert)
              case None           ⇒ element.addAttribute(qName, controlAlert)
            }

            annotated = true
          }
        }

        // Iterate all controls with warnings and try to annotate the associated element nodes
        controlsIterator foreach annotateElementIfPossible

        // If there is any annotation, make sure the attribute's namespace prefix is in scope on the root
        // element
        if (annotated)
          addRootElementNamespace(doc)
      }
    }

  def isSatisfiesValidity(
    startNode        : Node,
    relevantHandling : RelevanceHandling)(implicit
    indentedLogger   : IndentedLogger
  ): Boolean =
    findFirstElementOrAttributeWith(
      startNode,
      relevantHandling match {
        case Keep | Remove ⇒ node ⇒ ! InstanceData.getValid(node)
        case Empty         ⇒ node ⇒ ! InstanceData.getValid(node) && InstanceData.getInheritedRelevant(node)
      }
    ) match {
      case Some(e: Element) ⇒
        logInvalidNode(e)
        false
      case Some(a: Attribute) ⇒
        logInvalidNode(a)
        false
      case Some(_) ⇒
        throw new IllegalArgumentException
      case None ⇒
        true
    }

  def logInvalidNode(node: Node)(implicit indentedLogger: IndentedLogger): Unit =
    if (indentedLogger.isDebugEnabled)
      node match {
        case e: Element ⇒
            indentedLogger.logDebug(
              "",
              "found invalid node",
              "element name",
              Dom4jUtils.elementToDebugString(e)
            )
        case a: Attribute ⇒
          indentedLogger.logDebug(
            "",
            "found invalid attribute",
            "attribute name",
            Dom4jUtils.attributeToDebugString(a),
            "parent element",
            Dom4jUtils.elementToDebugString(a.getParent)
          )
        case _ ⇒
          throw new IllegalArgumentException
      }

  def requestedSerialization(
    xformsSerialization : Option[String],
    xformsMethod        : String,
    httpMethod          : HttpMethod
  ): Option[String] =
      xformsSerialization flatMap  (_.trimAllToOpt) orElse defaultSerialization(xformsMethod, httpMethod)

  def getRequestedSerializationOrNull(
    xformsSerialization : Option[String],
    xformsMethod        : String,
    httpMethod          : HttpMethod
  ): String =
    requestedSerialization(xformsSerialization, xformsMethod, httpMethod).orNull

  private object Private {

    def defaultSerialization(xformsMethod: String, httpMethod: HttpMethod): Option[String] =
      xformsMethod.trimAllToOpt collect {
        case "multipart-post"                                                      ⇒ "multipart/related"
        case "form-data-post"                                                      ⇒ "multipart/form-data"
        case "urlencoded-post"                                                     ⇒ "application/x-www-form-urlencoded"
        case _ if httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT ||
                  httpMethod == HttpMethod.LOCK || httpMethod == HttpMethod.UNLOCK ⇒ "application/xml"
        case _ if httpMethod == HttpMethod.GET  || httpMethod == HttpMethod.DELETE ⇒ "application/x-www-form-urlencoded"
      }

    def annotateNonRelevantElements(doc: Document, attQname: QName): Unit = {

      def processElem(e: Element): Unit =
        if (! InstanceData.getInheritedRelevant(e))
          e.addAttribute(attQname, "false")
        else {
          e.removeAttribute(attQname)
          e.elements.asScala foreach processElem
        }

      processElem(doc.getRootElement)
    }

    def findFirstNonRelevantElementOrAttribute(startNode: Node): Option[Node] =
      findFirstElementOrAttributeWith(startNode, node ⇒ ! InstanceData.getInheritedRelevant(node))

    def findFirstElementOrAttributeWith(startNode: Node, check: Node ⇒ Boolean): Option[Node] = {

      val breaks = new scala.util.control.Breaks
      import breaks._

      var foundNode: Node = null

      tryBreakable[Option[Node]] {
        startNode.accept(
          new VisitorSupport {
            override def visit(element: Element)     = checkNodeAndBreakIfFail(element)
            override def visit(attribute: Attribute) = checkNodeAndBreakIfFail(attribute)

            def checkNodeAndBreakIfFail(node: Node) =
              if (check(node)) {
                foundNode = node
                break()
              }
          }
        )
        None
      } catchBreak {
        Some(foundNode)
      }
    }

    def addRootElementNamespace(doc: Document) =
      doc.getRootElement.addNamespace(XXFORMS_NAMESPACE_SHORT.prefix, XXFORMS_NAMESPACE_SHORT.uri)
  }
}