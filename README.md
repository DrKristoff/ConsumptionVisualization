# ConsumptionVisualization

This project is an android application that displays live automobile fuel consumption through an onscreen drip animation.  It was created using code from the android-obd-reader library as well as the Google Liquid Fun library.

The app connects to a Bluetooth OBD2 device and reads its Mass Air Flow reading.  This data is delivered in g/s.  The app uses a Mass Air Flow ratio of 15 for gasoline.  So if the OBD2 reports 15g/s of air that corresponds to 1g/s of fuel.  Each water particle in the simulation represents a drop of fuel, which is roughly 1/20 of a gram.  

Youtube demo below
[![App Live Demo](https://img.youtube.com/vi/bv8jJJZxU2s/0.jpg)](https://www.youtube.com/watch?v=bv8jJJZxU2s)
