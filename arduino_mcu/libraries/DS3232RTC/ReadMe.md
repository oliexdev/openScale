# Arduino DS3232RTC Library v1.0 #
https://github.com/JChristensen/DS3232RTC
ReadMe file  
Jack Christensen Mar 2013

![CC BY-SA](http://mirrors.creativecommons.org/presskit/buttons/80x15/png/by-sa.png)

## Introduction ##
**DS3232RTC** is an Arduino library that supports the Maxim Integrated DS3232 and DS3231 Real-Time Clocks. This library is intended to be used with the [Arduino Time library](http://www.arduino.cc/playground/Code/Time).

The **DS3232RTC** library is a drop-in replacement for the DS1307RTC.h library by Michael Margolis that is supplied with the Arduino Time library above. To change from using a DS1307 RTC to an DS323x RTC, it is only necessary to use `#include <DS3232RTC.h>` instead of `#include <DS1307RTC.h>`.

**DS3232RTC** also implements functions to support the additional features of the DS3232 and DS3231. The DS3231 has the same features as the DS3232 except: (1) Battery-backed SRAM, (2) Battery-backed 32kHz output (BB32kHz bit in Control/Status register 0x0F), and (3) Adjustable temperature sensor sample rate (CRATE1:0 bits in the Control/Status register).

"Arduino DS3232RTC Library" by Jack Christensen is licensed under CC BY-SA 4.0.

## Installation ##
To use the **DS3232RTC** library:  
- Go to https://github.com/JChristensen/DS3232RTC, click the **Download ZIP** button and save the ZIP file to a convenient location on your PC.
- Uncompress the downloaded file.  This will result in a folder containing all the files for the library, that has a name that includes the branch name, usually **DS3232RTC-master**.
- Rename the folder to just **DS3232RTC**.
- Copy the renamed folder to the Arduino sketchbook\libraries folder.

## Examples ##
The following example sketches are included with the **DS3232RTC** library:
- **SetSerial:** Set the RTC's date and time from the Arduino serial monitor. Displays date, time and temperature.
- **TimeRTC:** Same as the example of the same name provided with the **Time** library, demonstrating the interchangeability of the **DS3232RTC** library with the **DS1307RTC** library.
- **tiny3232_KnockBang:** Demonstrates interfacing an ATtiny45/85 to a DS3231 or DS3232 RTC.

## Usage notes ##

When using the **DS3232RTC** library, the user is responsible for ensuring that reads and writes do not exceed the device's address space (0x00-0x12 for DS3231, 0x00-0xFF for DS3232); no bounds checking is done by the library.            

Similar to the **DS1307RTC** library, the **DS3232RTC** library instantiates an RTC object; the user does not need to do this.

To use the **DS3232RTC** library, the Time and Wire libraries must also be included.  For brevity, these includes are not repeated in the examples below:
```c++
#include <DS3232RTC.h>    //http://github.com/JChristensen/DS3232RTC
#include <Time.h>         //http://www.arduino.cc/playground/Code/Time
#include <Wire.h>         //http://arduino.cc/en/Reference/Wire (included with Arduino IDE)
```

## Enumerations ##
### SQWAVE_FREQS_t
##### Description
Symbolic names used with the squareWave() method (described below).
##### Values
- SQWAVE_NONE
- SQWAVE_1_HZ
- SQWAVE_1024_HZ
- SQWAVE_4096_HZ
- SQWAVE_8192_HZ

### ALARM_TYPES_t
##### Description
Symbolic names used with the setAlarm() method (described below).
##### Values for Alarm 1
- ALM1_EVERY_SECOND -- causes an alarm once per second.
- ALM1_MATCH_SECONDS -- causes an alarm when the seconds match (i.e. once per minute).
- ALM1_MATCH_MINUTES -- causes an alarm when the minutes *and* seconds match.
- ALM1_MATCH_HOURS -- causes an alarm when the hours *and* minutes *and* seconds match.
- ALM1_MATCH_DATE -- causes an alarm when the date of the month *and* hours *and* minutes *and* seconds match.
- ALM1_MATCH_DAY -- causes an alarm when the day of the week *and* hours *and* minutes *and* seconds match.

##### Values for Alarm 2
- ALM2_EVERY_MINUTE -- causes an alarm once per minute.
- ALM2_MATCH_MINUTES -- causes an alarm when the minutes match (i.e. once per hour).
- ALM2_MATCH_HOURS -- causes an alarm when the hours *and* minutes match.
- ALM2_MATCH_DATE -- causes an alarm when the date of the month *and* hours *and* minutes match.
- ALM2_MATCH_DAY -- causes an alarm when the day of the week *and* hours *and* minutes match.


## Methods for setting and reading the time ##

###get(void)
#####Description
Reads the current date and time from the RTC and returns it as a *time_t* value. Returns zero if an I2C error occurs (RTC not present, etc.).
#####Syntax
`RTC.get();`
#####Parameters
None.
#####Returns
Current date and time *(time_t)*
#####Example
```c++
time_t myTime;
myTime = RTC.get();
```

###set(time_t t)
#####Description
Sets the RTC date and time to the given *time_t* value.
#####Syntax
`RTC.set(t);`
#####Parameters
**t:** The date and time to set the RTC to *(time_t)*
#####Returns
I2C status *(byte)*. Returns zero if successful.
#####Example
```c++
//this example first sets the system time (maintained by the Time library) to
//a hard-coded date and time, and then sets the RTC from the system time.
//the setTime() function is part of the Time library.
setTime(23, 31, 30, 13, 2, 2009);   //set the system time to 23h31m30s on 13Feb2009
RTC.set(now());                     //set the RTC from the system time
```

###read(tmElements_t &tm)
#####Description
Reads the current date and time from the RTC and returns it as a *tmElements_t* structure. See the [Arduino Time library](http://www.arduino.cc/playground/Code/Time) for details on the *tmElements_t* structure.
#####Syntax
`RTC.read(tm);`
#####Parameters
**tm:** Address of a *tmElements_t* structure to which the date and time are returned.
#####Returns
I2C status *(byte)*. Returns zero if successful. The date and time read from the RTC are returned to the **tm** parameter.
#####Example
```c++
tmElements_t tm;
RTC.read(tm);
Serial.print(tm.Hour, DEC);
Serial.print(':');
Serial.print(tm.Minute,DEC);
Serial.print(':');
Serial.println(tm.Second,DEC);
```

###write(tmElements_t &tm)
#####Description
Sets the RTC to the date and time given by a *tmElements_t* structure.
#####Syntax
`RTC.write(tm);`
#####Parameters
**tm:** Address of a *tmElements_t* structure used to set the date and time.
#####Returns
I2C status *(byte)*. Returns zero if successful.
#####Example
```c++
tmElements_t tm;
tm.Hour = 23;             //set the tm structure to 23h31m30s on 13Feb2009
tm.Minute = 31;
tm.Minute = 30;
tm.Day = 13;
tm.Month = 2;
tm.Year = 2009 - 1970;    //tmElements_t.Year is the offset from 1970
RTC.write(tm);            //set the RTC from the tm structure
```

## Methods for reading and writing RTC registers or static RAM (SRAM) for the DS3232 ##
The DS3232RTC.h file defines symbolic names for the timekeeping, alarm, status and control registers. These can be used for the addr argument in the functions below.

###writeRTC(byte addr, byte *values, byte nBytes)
#####Description
Write one or more bytes to RTC memory.
#####Syntax
`RTC.writeRTC(addr, values, nbytes);`
#####Parameters
**addr:** First SRAM address to write *(byte)*. The valid address range is 0x00-0x12 for DS3231, 0x00-0xFF for DS3232. The general-purpose SRAM for the DS3232 begins at address 0x14. Address is not checked for validity by the library.
**values:** An array of values to write _(*byte)_  
**nBytes:** Number of bytes to write *(byte)*. Must be between 1 and 31 (Wire library limitation) but is not checked by the library. 
#####Returns
I2C status *(byte)*. Returns zero if successful.
#####Example
```c++
//write 1, 2, ..., 8 to the first eight DS3232 SRAM locations
byte buf[8] = {1, 2, 3, 4, 5, 6, 7, 8};
RTC.sramWrite(0x14, buf, 8);
```

###writeRTC(byte addr, byte value)
#####Description
Write a single byte to RTC memory.
#####Syntax
`RTC.writeRTC(addr, value);`
#####Parameters
**addr:** SRAM address to write *(byte)*. The valid address range is 0x00-0x12 for DS3231, 0x00-0xFF for DS3232. The general-purpose SRAM for the DS3232 begins at address 0x14. Address is not checked for validity by the library.
**value:** Value to write _(byte)_  
#####Returns
I2C status *(byte)*. Returns zero if successful.
#####Example
```c++
RTC.writeRTC(3, 14);   //write the value 14 to SRAM address 3
```

###readRTC(byte addr, byte *values, byte nBytes)
#####Description
Read one or more bytes from RTC RAM.
#####Syntax
`RTC.readRTC(addr, values, nbytes);`
#####Parameters
**addr:** First SRAM address to read *(byte)*. The valid address range is 0x00-0x12 for DS3231, 0x00-0xFF for DS3232. The general-purpose SRAM for the DS3232 begins at address 0x14. Address is not checked for validity by the library.
**values:** An array to receive the values read _(*byte)_  
**nBytes:** Number of bytes to read *(byte)*. Must be between 1 and 32 (Wire library limitation) but is not checked by the library. 
#####Returns
I2C status *(byte)*. Returns zero if successful.
#####Example
```c++
//read the last eight locations of SRAM into buf
byte buf[8];
RTC.sramRead(248, buf, 8);
```

###readRTC(byte addr)
#####Description
Reads a single byte from RTC RAM.
#####Syntax
`RTC.readRTC(addr);`
#####Parameters
**addr:** SRAM address to read *(byte)*. The valid address range is 0x00-0x12 for DS3231, 0x00-0xFF for DS3232. The general-purpose SRAM for the DS3232 begins at address 0x14. Address is not checked for validity by the library.
#####Returns
Value read from the RTC *(byte)*
#####Example
```c++
byte val;
val = RTC.readRTC(3);  //read the value from SRAM location 3
```

## Alarm methods ##
The DS3232 and DS3231 have two alarms. Alarm1 can be set to seconds precision; Alarm2 can only be set to minutes precision.

###setAlarm(ALARM_TYPES_t alarmType, byte seconds, byte minutes, byte hours, byte daydate)
#####Description
Set an alarm time. Sets the alarm registers only.  To cause the INT pin to be asserted on alarm match, use alarmInterrupt(). This method can set either Alarm 1 or Alarm 2, depending on the value of alarmType (use the ALARM_TYPES_t enumeration above). When setting Alarm 2, the seconds value must be supplied but is ignored, recommend using zero. (Alarm 2 has no seconds register.)

#####Syntax
`RTC.setAlarm(alarmType, seconds, minutes, hours, dayOrDate);`
#####Parameters
**alarmType:** A value from the ALARM_TYPES_t enumeration, above. *(ALARM_TYPES_t)*  
**seconds:** The seconds value to set the alarm to. *(byte)*  
**minutes:** The minutes value to set the alarm to. *(byte)*  
**hours:** The hours value to set the alarm to. *(byte)*  
**dayOrDate:** The day of the week or the date of the month. For day of the week, use a value from the Time library timeDayOfWeek_t enumeration, i.e. dowSunday, dowMonday, dowTuesday, dowWednesday, dowThursday, dowFriday, dowSaturday. *(byte)*  
#####Returns
None.
#####Example
```c++
//Set Alarm1 for 12:34:56 on Sunday
RTC.setAlarm(ALM1_MATCH_DAY, 56, 34, 12, dowSunday);
```

###setAlarm(ALARM_TYPES_t alarmType, byte minutes, byte hours, byte daydate)
#####Description
Set an alarm time. Sets the alarm registers only.  To cause the INT pin to be asserted on alarm match, use alarmInterrupt().  This method can set either Alarm 1 or Alarm 2, depending on the value of alarmType (use the ALARM_TYPES_t enumeration above). However, when using this method to set Alarm 1, the seconds value is set to zero. (Alarm 2 has no seconds register.)

#####Syntax
`RTC.setAlarm(alarmType, minutes, hours, dayOrDate);`
#####Parameters
**alarmType:** A value from the ALARM_TYPES_t enumeration, above. *(ALARM_TYPES_t)*  
**minutes:** The minutes value to set the alarm to. *(byte)*  
**hours:** The hours value to set the alarm to. *(byte)*  
**dayOrDate:** The day of the week or the date of the month. For day of the week, use a value from the Time library timeDayOfWeek_t enumeration, i.e. dowSunday, dowMonday, dowTuesday, dowWednesday, dowThursday, dowFriday, dowSaturday. *(byte)*  
#####Returns
None.
#####Example
```c++
//Set Alarm2 for 12:34 on the 4th day of the month
RTC.setAlarm(ALM1_MATCH_DATE, 34, 12, 4);
```

###alarmInterrupt(byte alarmNumber, boolean alarmEnabled)
#####Description
Enable or disable an alarm "interrupt". Note that this "interrupt" causes the RTC's INT pin to be asserted. To use this signal as an actual interrupt to a microcontroller, it will need to be connected properly and programmed in the application firmware.
on the RTC.   
#####Syntax
`RTC.alarmInterrupt(alarmNumber, enable);`
#####Parameters
**alarmNumber:** The number of the alarm to enable or disable, ALARM_1 or ALARM_2 *(byte)*  
**alarmEnabled:** true or false *(boolean)*  
#####Returns
None.
#####Example
```c++
RTC.alarmInterrupt(ALARM_1, true);      //assert the INT pin when Alarm1 occurs.
RTC.alarmInterrupt(ALARM_2, false);     //disable Alarm2
```

###alarm(byte alarmNumber)
#####Description
Tests whether an alarm has been triggered. If the alarm was triggered, returns true and resets the alarm flag in the RTC, else returns false.
#####Syntax
`RTC.alarm(alarmNumber);`
#####Parameters
**alarmNumber:** The number of the alarm to test, ALARM_1 or ALARM_2 *(byte)*  
#####Returns
Description *(type)*
#####Example
```c++
if ( RTC.alarm(ALARM_1) ) {		//has Alarm1 triggered?
	//yes, act on the alarm
}
else {
	//no alarm
}
```

## Other methods ##
###temperature(void)
#####Description
Returns the RTC temperature.
#####Syntax
`RTC.temperature();`
#####Parameters
None.
#####Returns
RTC temperature as degrees Celsius times four. *(int)*
#####Example
```c++
int t = RTC.temperature();
float celsius = t / 4.0;
float fahrenheit = celsius * 9.0 / 5.0 + 32.0;
```

###squareWave(SQWAVE_FREQS_t freq)
#####Description
Enables or disables the square wave output.
#####Syntax
`RTC.squareWave(freq);`
#####Parameters
**freq:** a value from the SQWAVE_FREQS_t enumeration above. *(SQWAVE_FREQS_t)*  
#####Returns
None.
#####Example
```c++
RTC.squareWave(SQWAVE_1_HZ);	//1 Hz square wave
RTC.squareWave(SQWAVE_NONE);	//no square wave
```

###oscStopped(void)
#####Description
Check whether the RTC oscillator is or was stopped. This may indicate that the RTC's time is not accurate.
#####Syntax
`RTC.oscStopped();`
#####Parameters
None.
#####Returns
True or false *(boolean)*
#####Example
```c++
if ( RTC.oscStopped() ) {		//check the oscillator
	//may be trouble
}
else {
	//all is well
}
```