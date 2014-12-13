/*----------------------------------------------------------------------*
 * Digital clock display using a DS3231/32 Real-Time Clock              *
 * and an ATtiny45/85 with a 1MHz system clock.                         *
 * Also seems to work with a DS1307 which is fairly similar but the     *
 * DS3232RTC library doesn't officially support it.                     *
 *                                                                      *
 * Tested with Arduino 1.0.5. Also Arduino-Tiny Core, TinyISP, and      *
 * TinyDebugKnockBang from http://code.google.com/p/arduino-tiny/       *
 *                                                                      *
 * Run TinyISP on an ATmega microcontroller that does not have an LED   *
 * connected to pin 13 (SCK). The LED causes problems because the SPI   *
 * pins are also the I2C pins on the ATtiny. Connect MISO, MOSI, SCK    *
 * on the ATmega to the corresponding pins on the ATtiny through 220Ω   *
 * resistors for safety. Use 4.7K pullup resistors on the ATtiny        *    
 * I2C bus.                                                             *
 *                                                                      *
 * Jack Christensen 21Aug2013                                           *
 *                                                                      *
 * This work is licensed under the Creative Commons Attribution-        *
 * ShareAlike 3.0 Unported License. To view a copy of this license,     *
 * visit http://creativecommons.org/licenses/by-sa/3.0/ or send a       *
 * letter to Creative Commons, 171 Second Street, Suite 300,            *
 * San Francisco, California, 94105, USA.                               *
 *----------------------------------------------------------------------*/ 

#include <DS3232RTC.h>             //http://github.com/JChristensen/DS3232RTC
#include <Time.h>                  //http://playground.arduino.cc/Code/Time
#include <TinyDebugKnockBang.h>    //http://code.google.com/p/arduino-tiny/
#include <TinyWireM.h>             //http://playground.arduino.cc/Code/USIi2c

void setup(void)
{
    Debug.begin(250000);
    
    //setSyncProvider() causes the Time library to synchronize with the
    //external RTC by calling RTC.get() every five minutes by default.
    setSyncProvider(RTC.get);
    Debug.print(F("RTC Sync"));
    if (timeStatus() != timeSet) Debug.print(F(" FAIL!"));
    Debug.println();
}

void loop(void)
{
    static time_t tLast;

    time_t t = now();
    if (t != tLast) {
        tLast = t;
        printDateTime(t);
        Debug.println();
    }
}

//print date and time to Serial
void printDateTime(time_t t)
{
    printDate(t);
    Debug.print(' ');
    printTime(t);
}

//print time to Serial
void printTime(time_t t)
{
    printI00(hour(t), ':');
    printI00(minute(t), ':');
    printI00(second(t), ' ');
}

//print date to Serial
void printDate(time_t t)
{
    printI00(day(t), 0);
    Debug.print(monthShortStr(month(t)));
    Debug.print(year(t), DEC);
}

//Print an integer in "00" format (with leading zero),
//followed by a delimiter character to Serial.
//Input value assumed to be between 0 and 99.
void printI00(int val, char delim)
{
    if (val < 10) Debug.print('0');
    Debug.print(val, DEC);;
    if (delim > 0) Debug.print(delim);
    return;
}
