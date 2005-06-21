/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.event;


/**
 * Internal XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE event.
 */
public class XXFormsValueChangeWithFocusChangeEvent extends XFormsEvent {
    private String newValue;
    private Object otherTargetObject;
    public XXFormsValueChangeWithFocusChangeEvent(Object targetObject, Object otherTargetObject, String newValue) {
        super(XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, targetObject, false, false);
        this.otherTargetObject = otherTargetObject;
        this.newValue = newValue;
    }

    public Object getOtherTargetObject() {
        return otherTargetObject;
    }

    public String getNewValue() {
        return newValue;
    }
}
