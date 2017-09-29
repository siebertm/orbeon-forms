/**
 * Copyright (C) 2017 Orbeon, Inc.
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
package org.orbeon.builder

import enumeratum.EnumEntry._
import enumeratum._
import org.orbeon.builder.BlockCache.Block
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.xforms._
import org.scalajs.dom.document
import org.scalajs.jquery.JQuery

import scala.scalajs.js

object SectionGridEditor {

  lazy val sectionGridEditorContainer               = $(".fb-section-grid-editor")
  lazy val rowEditorContainer                       = $(".fb-row-editor")

  var currentSectionGridBodyOpt : Option[Block] = None
  var currentRowPosOpt          : Option[Int]   = None

  sealed trait GridSectionEditor extends EnumEntry with Hyphencase
  object GridSectionEditor extends Enum[GridSectionEditor] {
    val values = findValues
    case object FbSectionDelete      extends GridSectionEditor
    case object FbSectionEditDetails extends GridSectionEditor
    case object FbSectionEditHelp    extends GridSectionEditor
    case object FbSectionMoveUp      extends GridSectionEditor
    case object FbSectionMoveDown    extends GridSectionEditor
    case object FbSectionMoveRight   extends GridSectionEditor
    case object FbSectionMoveLeft    extends GridSectionEditor
    case object FbSectionEditors     extends GridSectionEditor
    case object FbGridEditDetails    extends GridSectionEditor
    case object FbGridDelete         extends GridSectionEditor

    // What class, if any, must be present on the corresponding container to the the editor to be enabled
    def enableClass(editor: GridSectionEditor): Option[String] =
      editor match {
        case FbSectionMoveUp    ⇒ Some("fb-can-move-up")
        case FbSectionMoveDown  ⇒ Some("fb-can-move-down")
        case FbSectionMoveRight ⇒ Some("fb-can-move-right")
        case FbSectionMoveLeft  ⇒ Some("fb-can-move-left")
        case _                  ⇒ None
      }
  }

  sealed case class RowEditor(selector: String    , eventName: String )
  val AddRowAbove = RowEditor(".icon-chevron-up"  , "fb-row-insert-above")
  val DeleteRow   = RowEditor(".icon-minus-sign"  , "fb-row-delete"   )
  val AddRowBelow = RowEditor(".icon-chevron-down", "fb-row-insert-below")
  val RowEditors  = List(AddRowAbove, DeleteRow, AddRowBelow)

  // Position editor when block becomes current
  Position.currentContainerChanged(
    containerCache = BlockCache.sectionGridBodyCache,
    wasCurrent = (_: Block) ⇒ (),
    becomesCurrent = (sectionGridBody: Block) ⇒ {
      currentSectionGridBodyOpt = Some(sectionGridBody)

      // Position the editor
      sectionGridEditorContainer.show()
      Position.offset(sectionGridEditorContainer, Position.Offset(
        // Use `.fr-body` left rather than the section left to account for sub-sections indentation
        left = Position.offset($(".fr-body")).left - sectionGridEditorContainer.outerWidth(),
        top  = sectionGridBody.top - Position.scrollTop()
      ))

      // Start by hiding all the icons
      sectionGridEditorContainer.children().hide()

      // Update triggers relevance for section
      if (sectionGridBody.el.is(BlockCache.SectionSelector)) {

        // Edit details and help are always visible
        sectionGridEditorContainer.children(".fb-section-edit-details, .fb-section-edit-help").show()

        // Hide/show section move icons
        val container = sectionGridBody.el.children(".fr-section-container")
        List("up", "right", "down", "left").foreach((direction) ⇒ {
          val relevant = container.hasClass("fb-can-move-" + direction)
          val trigger  = sectionGridEditorContainer.children(".fb-section-move-" + direction)
          if (relevant) trigger.show()
        })

        // Hide/show delete icon
        val deleteTrigger = sectionGridEditorContainer.children(".delete-section-trigger")
        if (container.is(".fb-can-delete")) deleteTrigger.show()
      }

      // Update triggers relevance for section
      if (sectionGridBody.el.is(BlockCache.GridBodySelector)) {
        sectionGridEditorContainer.children(".fb-grid-edit-details, .fb-grid-delete").show()
      }
    }
  )

  // Hide editor when the pointer gets out of the Form Builder main area
  Position.currentContainerChanged(
    containerCache = BlockCache.fbMainCache,
    wasCurrent = (_: Block) ⇒ {
      sectionGridEditorContainer.hide()
      rowEditorContainer.hide()
      currentSectionGridBodyOpt = None
      currentRowPosOpt          = None
    },
    becomesCurrent = (_: Block) ⇒ ( /* NOP */ )
  )

  // Position row editor
  Position.onUnderPointerChange {
    withCurrentGridBody((currentGridBody) ⇒ {

      // Get the height of each row track
      val rowsHeight =
        currentGridBody.el
          .css("grid-template-rows")
          .splitTo[List]()
          .map((hPx) ⇒ hPx.substring(0, hPx.indexOf("px")))
          .map(_.toDouble)

      case class TopBottom(top: Double, bottom: Double)

      // For each row track, find its top/bottom
      val rowsTopBottom = {
        val gridBodyTop = currentGridBody.top
        val zero = List(TopBottom(0, gridBodyTop))
        rowsHeight.foldLeft(zero) { (soFar: List[TopBottom], rowHeight: Double) ⇒
          val lastBottom = soFar.last.bottom
          val newTopBottom = TopBottom(lastBottom, lastBottom + rowHeight)
          soFar :+ newTopBottom
        }.drop(1)
      }

      // Find top/bottom of the row track the pointer is on
      val pointerRowTopBottomIndexOpt = {
        val pointerTop = Position.pointerPos.top + Position.scrollTop()
        rowsTopBottom.zipWithIndex.find { case (topBottom, _) ⇒
          topBottom.top <= pointerTop && pointerTop <= topBottom.bottom
        }
      }

      // Find where to position the row editor on the left
      val containerLeft = Position.offset(gridFromGridBody(currentGridBody)).left

      // Position row editor
      pointerRowTopBottomIndexOpt.foreach((pointerRowTopBottom) ⇒ {
        rowEditorContainer.show()
        rowEditorContainer.children().hide()

        val rowTop    = pointerRowTopBottom._1.top
        val rowBottom = pointerRowTopBottom._1.bottom
        val rowHeight = rowBottom - rowTop
        val rowIndex  = pointerRowTopBottom._2

        def positionElWithClass(selector: String, topOffset: (JQuery) ⇒ Double): Unit = {
          val elem = rowEditorContainer.children(selector)
          elem.show()
          Position.offset(
            el = elem,
            offset = Position.Offset(
              left = containerLeft,
              top  = topOffset(elem) - Position.scrollTop()
            )
          )
        }

        currentRowPosOpt = Some(rowIndex + 1)
        positionElWithClass(AddRowAbove.selector, (_) ⇒ rowTop)
        positionElWithClass(DeleteRow.selector  , (e) ⇒ rowTop + rowHeight/2 - e.height()/2)
        positionElWithClass(AddRowBelow.selector, (e) ⇒ rowBottom - e.height())
      })
    })
  }

  def gridFromGridBody(block: Block): JQuery = {
    assert(block.el.is(BlockCache.GridBodySelector))
    block.el.closest(BlockCache.GridSelector)
  }

  def withCurrentGridBody(fn: Block ⇒ Unit): Unit =
    currentSectionGridBodyOpt.foreach((currentSectionGridBody) ⇒
      if (currentSectionGridBody.el.is(BlockCache.GridBodySelector))
        fn(currentSectionGridBody)
    )

  // Register listener on editor icons
  $(document).ready(() ⇒ {
    GridSectionEditor.values.foreach((editor) ⇒ {
      val editorName = editor.entryName
      val iconEl = sectionGridEditorContainer.children(s".$editorName")
      iconEl.on("click", () ⇒ {
        currentSectionGridBodyOpt.foreach((currentSectionGridBody) ⇒ {
          val isSection = currentSectionGridBody.el.is(BlockCache.SectionSelector)
          val sectionGrid =
            if (isSection) currentSectionGridBody.el
            else gridFromGridBody(currentSectionGridBody)
          DocumentAPI.dispatchEvent(
            targetId   = sectionGrid.attr("id").get,
            eventName  = editorName
          )
        })
      })
    })
    RowEditors.foreach((rowEditor) ⇒ {
      val iconEl = rowEditorContainer.children(rowEditor.selector)
      iconEl.on("click", () ⇒
        withCurrentGridBody((currentGridBody) ⇒
          currentRowPosOpt.foreach((currentRowPos) ⇒
            DocumentAPI.dispatchEvent(
              targetId   = gridFromGridBody(currentGridBody).attr("id").get,
              eventName  = rowEditor.eventName,
              properties = js.Dictionary(
                "fb-row-pos" → currentRowPos.toString
              )
            )
          )
        )
      )
    })
  })
}