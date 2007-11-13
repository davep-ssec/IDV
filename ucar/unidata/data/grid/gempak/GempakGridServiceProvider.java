/*
 * $Id: IDV-Style.xjs,v 1.3 2007/02/16 19:18:30 dmurray Exp $
 *
 * Copyright 1997-2007 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */


package ucar.unidata.data.grid.gempak;


import ucar.ma2.*;

import ucar.nc2.*;
import ucar.nc2.dt.fmr.FmrcCoordSys;
import ucar.nc2.util.CancelTask;

import ucar.unidata.io.RandomAccessFile;

import java.io.IOException;

import java.util.List;


/**
 * An IOSP for Gempak Grid data
 *
 * @author IDV Development Team
 * @version $Revision: 1.3 $
 */
public class GempakGridServiceProvider extends GempakIOServiceProvider {

    /** FMRC coordinate system */
    protected FmrcCoordSys fmrcCoordSys;

    /** debug timing flag */
    private boolean debug = false;

    /**
     * Is this a valid file?
     *
     * @param raf  RandomAccessFile to check
     *
     * @return true if a valid Gempak grid file
     *
     * @throws IOException  problem reading file
     */
    public boolean isValidFile(RandomAccessFile raf) throws IOException {
        try {
            gemreader = new GempakGridReader(raf);
        } catch (IOException ioe) {
            return false;
        }
        return true;
    }

    /**
     * Open the service provider for reading.
     * @param raf  file to read from
     * @param ncfile  netCDF file we are writing to (memory)
     * @param cancelTask  task for cancelling
     *
     * @throws IOException  problem reading file
     */
    public void open(RandomAccessFile raf, NetcdfFile ncfile,
                     CancelTask cancelTask)
            throws IOException {
        super.open(raf, ncfile, cancelTask);
        if (gemreader == null) {
            gemreader = new GempakGridReader();
        }
        gemreader.init(raf);
        GridIndex index = ((GempakGridReader) gemreader).getGridIndex();
        GempakLookup lookup =
            new GempakLookup(
                (GempakGridRecord) index.getGridRecords().get(0));
        GridIndexToNC delegate = new GridIndexToNC();
        delegate.open(index, lookup, 4, ncfile, fmrcCoordSys, cancelTask);
        ncfile.finish();

    }

    /**
     * Set the special object on this IOSP
     *
     * @param special  isn't that special?
     */
    public void setSpecial(Object special) {
        if (special instanceof FmrcCoordSys) {
            fmrcCoordSys = (FmrcCoordSys) special;
        }
    }

    /**
     * Read the data for the variable
     * @param v2  Variable to read
     * @param section   section infomation
     * @return Array of data
     *
     * @throws IOException problem reading from file
     * @throws InvalidRangeException  invalid Range
     */
    public Array readData(Variable v2, List section)
            throws IOException, InvalidRangeException {
        long start = System.currentTimeMillis();

        Array dataArray = Array.factory(DataType.FLOAT.getClassType(),
                                        Range.getShape(section));
        GridVariable  pv        = (GridVariable) v2.getSPobject();

        int           count     = 0;
        Range         timeRange = (Range) section.get(count++);
        Range         levRange  = pv.hasVert()
                                  ? (Range) section.get(count++)
                                  : null;
        Range         yRange    = (Range) section.get(count++);
        Range         xRange    = (Range) section.get(count);

        IndexIterator ii        = dataArray.getIndexIteratorFast();

        // loop over time
        for (int timeIdx = timeRange.first(); timeIdx <= timeRange.last();
                timeIdx += timeRange.stride()) {
            if (pv.hasVert()) {
                readLevel(v2, timeIdx, levRange, yRange, xRange, ii);
            } else {
                readXY(v2, timeIdx, 0, yRange, xRange, ii);
            }
        }

        if (debug) {
            long took = System.currentTimeMillis() - start;
            System.out.println("  read data took=" + took + " msec ");
        }

        return dataArray;
    }

    // loop over level

    /**
     * Read a level
     *
     * @param v2 _more_
     * @param timeIdx _more_
     * @param levelRange _more_
     * @param yRange _more_
     * @param xRange _more_
     * @param ii _more_
     *
     * @throws IOException _more_
     * @throws InvalidRangeException _more_
     */
    private void readLevel(Variable v2, int timeIdx, Range levelRange,
                           Range yRange, Range xRange, IndexIterator ii)
            throws IOException, InvalidRangeException {
        for (int levIdx = levelRange.first(); levIdx <= levelRange.last();
                levIdx += levelRange.stride()) {
            readXY(v2, timeIdx, levIdx, yRange, xRange, ii);
        }
    }

    // read one product

    /**
     * _more_
     *
     * @param v2 _more_
     * @param timeIdx _more_
     * @param levIdx _more_
     * @param yRange _more_
     * @param xRange _more_
     * @param ii _more_
     *
     * @throws IOException _more_
     * @throws InvalidRangeException _more_
     */
    private void readXY(Variable v2, int timeIdx, int levIdx, Range yRange,
                        Range xRange, IndexIterator ii)
            throws IOException, InvalidRangeException {
        Attribute         att           = v2.findAttribute("missing_value");
        float             missing_value = (att == null)
                                          ? -9999.0f
                                          : att.getNumericValue()
                                              .floatValue();

        GridVariable      pv            = (GridVariable) v2.getSPobject();
        GridHorizCoordSys hsys          = pv.getHorizCoordSys();
        int               nx            = hsys.getNx();

        GridRecord        record        = pv.findRecord(timeIdx, levIdx);
        if (debug) {
            System.out.println(record);
        }
        if (record == null) {
            int xyCount = yRange.length() * xRange.length();
            for (int j = 0; j < xyCount; j++) {
                ii.setFloatNext(missing_value);
            }
            return;
        }

        // otherwise read it
        float[] data;
        try {
            data = _readData(record);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        for (int y = yRange.first(); y <= yRange.last();
                y += yRange.stride()) {
            for (int x = xRange.first(); x <= xRange.last();
                    x += xRange.stride()) {
                int index = y * nx + x;
                ii.setFloatNext(data[index]);
            }
        }
    }

    /**
     * _more_
     *
     * @param v2 _more_
     * @param timeIdx _more_
     * @param levIdx _more_
     *
     * @return _more_
     *
     * @throws InvalidRangeException _more_
     */
    private boolean isMissingXY(Variable v2, int timeIdx, int levIdx)
            throws InvalidRangeException {
        GridVariable pv = (GridVariable) v2.getSPobject();
        if ((timeIdx < 0) || (timeIdx >= pv.getNTimes())) {
            throw new InvalidRangeException("timeIdx=" + timeIdx);
        }
        if ((levIdx < 0) || (levIdx >= pv.getVertNlevels())) {
            throw new InvalidRangeException("levIdx=" + levIdx);
        }
        return (null == pv.findRecord(timeIdx, levIdx));
    }

    /**
     * Read the data for this GridRecord
     *
     * @param gr   grid identifier
     *
     * @return  the data (or null)
     *
     * @throws IOException  problem reading the data
     */
    protected float[] _readData(GridRecord gr) throws IOException {
        return ((GempakGridReader) gemreader).readGrid(
            ((GempakGridRecord) gr).gridNumber);
    }

}

