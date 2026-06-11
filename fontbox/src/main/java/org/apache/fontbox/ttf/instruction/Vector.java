/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fontbox.ttf.instruction;

/**
 * A 2D unit vector in F2Dot14 fixed point, used for the projection, freedom and dual-projection
 * vectors of the TrueType graphics state. Kept as a mutable class (not {@code Point2D.Float}) so the
 * interpreter can stay in integer math; {@link GraphicsState} deep-copies these on clone to avoid the
 * shared-reference contamination bug that broke prior Java implementations.
 *
 * @author Apache PDFBox
 */
public class Vector
{
    private int x;
    private int y;

    /**
     * @param x the x component in F2Dot14
     * @param y the y component in F2Dot14
     */
    public Vector(int x, int y)
    {
        this.x = x;
        this.y = y;
    }

    /**
     * @return the x axis unit vector (1, 0)
     */
    public static Vector xAxis()
    {
        return new Vector(Fixed.ONE_F2DOT14, 0);
    }

    /**
     * @return the y axis unit vector (0, 1)
     */
    public static Vector yAxis()
    {
        return new Vector(0, Fixed.ONE_F2DOT14);
    }

    /**
     * @return the x component in F2Dot14
     */
    public int getX()
    {
        return x;
    }

    /**
     * @return the y component in F2Dot14
     */
    public int getY()
    {
        return y;
    }

    /**
     * @param x the x component in F2Dot14
     * @param y the y component in F2Dot14
     */
    public void set(int x, int y)
    {
        this.x = x;
        this.y = y;
    }

    /**
     * @return an independent copy of this vector
     */
    public Vector copy()
    {
        return new Vector(x, y);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof Vector))
        {
            return false;
        }
        Vector other = (Vector) obj;
        return x == other.x && y == other.y;
    }

    @Override
    public int hashCode()
    {
        return 31 * x + y;
    }

    @Override
    public String toString()
    {
        return "Vector(" + x + ", " + y + ")";
    }
}
