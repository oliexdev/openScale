/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core.evaluation;

public class EvaluationResult {
    public enum EVAL_STATE {LOW, NORMAL, HIGH, UNDEFINED}

    public float value;
    public float lowLimit;
    public float highLimit;
    public EVAL_STATE eval_state;

    public EvaluationResult(float value, float lowLimit, float highLimit, EVAL_STATE eval_state)
    {
        this.value = value;
        this.lowLimit = lowLimit;
        this.highLimit = highLimit;
        this.eval_state = eval_state;
    }
}
