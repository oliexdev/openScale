// **** INCLUDES *****
#include "LowPower.h"

void setup()
{
    // No setup is required for this library
}

void loop() 
{
    // Enter power down state for 8 s with ADC and BOD module disabled
    LowPower.powerDown(SLEEP_8S, ADC_OFF, BOD_OFF);  
    
    // Do something here
    // Example: Read sensor, data logging, data transmission.
}
