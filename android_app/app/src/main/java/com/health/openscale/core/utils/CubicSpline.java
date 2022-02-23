package com.health.openscale.core.utils;

import com.github.mikephil.charting.data.Entry;

import java.util.List;
import Jama.Matrix;

public class CubicSpline {

    private final List<Entry> lineEntries;
    double[] bb;
    double[] d;
    Matrix C;

    public CubicSpline(List<Entry> lineEntries){
        this.lineEntries = lineEntries;
    }

    public void calculate(){
        double[][] a = new double[this.lineEntries.size()][this.lineEntries.size()];
        double[] b = new double[this.lineEntries.size()];

        // border conditions
        a[0][0] = a[this.lineEntries.size()-1][this.lineEntries.size()-1] = 1;
        b[0] = b[this.lineEntries.size()-1] = 0;

        for(int i=1;i<this.lineEntries.size()-1;i++){
            float x0 = this.lineEntries.get(i-1).getX();
            float x1 = this.lineEntries.get(i).getX();
            float x2 = this.lineEntries.get(i+1).getX();

            float y0 = this.lineEntries.get(i-1).getY();
            float y1 = this.lineEntries.get(i).getY();
            float y2 = this.lineEntries.get(i+1).getY();

            a[i][i-1] = x1-x0;
            a[i][i] = 2.*(x2-x0); // 2*(x1-x0 + x2-x1);
            a[i][i+1] = x2-x1;
            b[i] = 3./(x2-x1) * (y2-y1) - 3./(x1-x0) * (y1-y0);
        }

        Matrix A = new Matrix(a);
        Matrix B = new Matrix(b, this.lineEntries.size());
        this.C = A.solve(B);

        this.bb = new double[this.lineEntries.size()-1];
        this.d = new double[this.lineEntries.size()-1];

        for(int i=1;i<this.lineEntries.size();i++){
            float x0 = this.lineEntries.get(i-1).getX();
            float x1 = this.lineEntries.get(i).getX();

            float y0 = this.lineEntries.get(i-1).getY();
            float y1 = this.lineEntries.get(i).getY();

            this.bb[i-1] = 1./(x1-x0) * (y1-y0) - (x1-x0)/3*(2*C.get(i-1, 0) + C.get(i, 0));
            this.d[i-1] = (C.get(i, 0)-C.get(i-1, 0)) / (3*(x1-x0));
        }
    }

    public double predict(double x){
        int j = this.lineEntries.size()-1;
        for(int i=0;i<this.lineEntries.size()-1;i++){
            if(this.lineEntries.get(i+1).getX() > x){
                j = i;
                break;
            }
        }

        double nx = this.lineEntries.get(j).getX();
        double ny = this.lineEntries.get(j).getY();

        return ny+this.bb[j]*(x-nx)+this.C.get(j,0)*Math.pow(x-nx, 2.)+this.d[j]*Math.pow(x-nx, 3.);
    }

}
