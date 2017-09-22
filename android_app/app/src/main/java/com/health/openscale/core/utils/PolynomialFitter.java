package com.health.openscale.core.utils;

/***************************************************************************
 *   Copyright (C) 2009 by Paul Lutus, Ian Clarke                          *
 *   lutusp@arachnoid.com, ian.clarke@gmail.com                            *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/

import java.util.ArrayList;
import java.util.List;

/**
 * A class to fit a polynomial to a (potentially very large) dataset.
 *
 * @author Paul Lutus <lutusp@arachnoid.com>
 * @author Ian Clarke <ian.clarke@gmail.com>
 *
 */

/*
 * Changelog:
 * 20100130: Add note about relicensing
 * 20091114: Modify so that points can be added after the curve is
 *           created, also some other minor fixes
 * 20091113: Extensively modified by Ian Clarke, main changes:
 *     - Should now be able to handle extremely large datasets
 *     - Use generic Java collections classes and interfaces
 *       where possible
 *     - Data can be fed to the fitter as it is available, rather
 *       than all at once
 *
 * The code that this is based on was obtained from: http://arachnoid.com/polysolve
 *
 * Note: I (Ian Clarke) am happy to release this code under a more liberal
 * license such as the LGPL, however Paul Lutus (the primary author) refuses
 * to do this on the grounds that the LGPL is not an open source license.
 * If you want to try to explain to him that the LGPL is indeed an open
 * source license, good luck - it's like talking to a brick wall.
 */
public class PolynomialFitter {

    private final int p, rs;

    private long n = 0;

    private final double[][] m;

    private final double[] mpc;
    /**
     * @param degree
     *            The degree of the polynomial to be fit to the data
     */
    public PolynomialFitter(final int degree) {
        assert degree > 0;
        p = degree + 1;
        rs = 2 * p - 1;
        m = new double[p][p + 1];
        mpc = new double[rs];
    }

    /**
     * Add a point to the set of points that the polynomial must be fit to
     *
     * @param x
     *            The x coordinate of the point
     * @param y
     *            The y coordinate of the point
     */
    public void addPoint(final double x, final double y) {
        assert !Double.isInfinite(x) && !Double.isNaN(x);
        assert !Double.isInfinite(y) && !Double.isNaN(y);
        n++;
        // process precalculation array
        for (int r = 1; r < rs; r++) {
            mpc[r] += Math.pow(x, r);
        }
        // process RH column cells
        m[0][p] += y;
        for (int r = 1; r < p; r++) {
            m[r][p] += Math.pow(x, r) * y;
        }
    }

    /**
     * Returns a polynomial that seeks to minimize the square of the total
     * distance between the set of points and the polynomial.
     *
     * @return A polynomial
     */
    public Polynomial getBestFit() {
        final double[] mpcClone = mpc.clone();
        final double[][] mClone = new double[m.length][];
        for (int x = 0; x < mClone.length; x++) {
            mClone[x] = m[x].clone();
        }

        mpcClone[0] += n;
        // populate square matrix section
        for (int r = 0; r < p; r++) {
            for (int c = 0; c < p; c++) {
                mClone[r][c] = mpcClone[r + c];
            }
        }
        gj_echelonize(mClone);
        final Polynomial result = new Polynomial(p);
        for (int j = 0; j < p; j++) {
            result.add(j, mClone[j][p]);
        }
        return result;
    }
    private double fx(final double x, final List<Double> terms) {
        double a = 0;
        int e = 0;
        for (final double i : terms) {
            a += i * Math.pow(x, e);
            e++;
        }
        return a;
    }
    private void gj_divide(final double[][] A, final int i, final int j, final int m) {
        for (int q = j + 1; q < m; q++) {
            A[i][q] /= A[i][j];
        }
        A[i][j] = 1;
    }

    private void gj_echelonize(final double[][] A) {
        final int n = A.length;
        final int m = A[0].length;
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            // look for a non-zero entry in col j at or below row i
            int k = i;
            while (k < n && A[k][j] == 0) {
                k++;
            }
            // if such an entry is found at row k
            if (k < n) {
                // if k is not i, then swap row i with row k
                if (k != i) {
                    gj_swap(A, i, j);
                }
                // if A[i][j] is not 1, then divide row i by A[i][j]
                if (A[i][j] != 1) {
                    gj_divide(A, i, j, m);
                }
                // eliminate all other non-zero entries from col j by
                // subtracting from each
                // row (other than i) an appropriate multiple of row i
                gj_eliminate(A, i, j, n, m);
                i++;
            }
            j++;
        }
    }

    private void gj_eliminate(final double[][] A, final int i, final int j, final int n, final int m) {
        for (int k = 0; k < n; k++) {
            if (k != i && A[k][j] != 0) {
                for (int q = j + 1; q < m; q++) {
                    A[k][q] -= A[k][j] * A[i][q];
                }
                A[k][j] = 0;
            }
        }
    }

    private void gj_swap(final double[][] A, final int i, final int j) {
        double temp[];
        temp = A[i];
        A[i] = A[j];
        A[j] = temp;
    }


    public static class Polynomial extends ArrayList<Double> {
        private static final long serialVersionUID = 1692843494322684190L;

        public Polynomial(final int p) {
            super(p);
        }

        public double getY(final double x) {
            double ret = 0;
            for (int p=0; p<size(); p++) {
                ret += get(p)*(Math.pow(x, p));
            }
            return ret;
        }

        @Override
        public String toString() {
            final StringBuilder ret = new StringBuilder();
            for (int x = size() - 1; x > -1; x--) {
                ret.append(get(x) + (x > 0 ? "x^" + x + " + " : ""));
            }
            return ret.toString();
        }
    }
}