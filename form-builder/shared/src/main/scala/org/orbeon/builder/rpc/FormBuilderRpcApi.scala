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
package org.orbeon.builder.rpc

trait FormBuilderRpcApi {

  def unsupportedBrowser(browserName: String, browserVersion: Double): Unit

  def controlUpdateLabelOrHintOrText (controlId: String, lhha: String, value: String, isHTML: Boolean): Unit

  def controlDelete       (controlId: String): Unit
  def controlEditDetails  (controlId: String): Unit
  def controlEditItems    (controlId: String): Unit
  def controlDnD          (controlId: String, destCellId: String, copy: Boolean): Unit

  def rowInsert           (controlId: String, position: Int, aboveBelowString: String): Unit // TODO: `AboveBelow` doesn't serialize correctly with Circe.
  def rowDelete           (controlId: String, position: Int): Unit

  def shrinkDown          (cellId: String): Unit
  def expandRight         (cellId: String): Unit
  def expandDown          (cellId: String): Unit
  def shrinkRight         (cellId: String): Unit

  def sectionDelete       (sectionId: String): Unit
  def sectionUpdateLabel  (sectionId: String, label: String): Unit
  def sectionEditHelp     (sectionId: String): Unit
  def sectionMove         (sectionId: String, directionString: String): Unit // TODO: `Direction` doesn't serialize correctly with Circe.

  def gridDelete          (gridId: String): Unit

  def containerEditDetails(containerId: String): Unit
  def containerCopy       (containerId: String): Unit
  def containerCut        (containerId: String): Unit
  def containerMerge      (containerId: String): Unit
}
