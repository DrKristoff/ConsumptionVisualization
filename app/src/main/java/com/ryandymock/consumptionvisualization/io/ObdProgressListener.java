package com.ryandymock.consumptionvisualization.io;

public interface ObdProgressListener {

    void stateUpdate(final ObdCommandJob job);

}