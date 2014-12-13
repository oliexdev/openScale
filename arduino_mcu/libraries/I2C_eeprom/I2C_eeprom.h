#ifndef I2C_EEPROM_H
#define I2C_EEPROM_H
//
//    FILE: I2C_eeprom.h
//  AUTHOR: Rob Tillaart
// PURPOSE: Simple I2C_eeprom library for Arduino with EEPROM 24LC256 et al.
// VERSION: 1.0.05
// HISTORY: See I2C_eeprom.cpp
//     URL: http://arduino.cc/playground/Main/LibraryForI2CEEPROM
//
// Released to the public domain
//

#include <Wire.h>

#if defined(ARDUINO) && ARDUINO >= 100
#include "Arduino.h"
#else
#include "WProgram.h"
#include "Wstring.h"
#include "Wiring.h"
#endif

#define I2C_EEPROM_VERSION "1.0.05"

// I2C_EEPROM_PAGESIZE must be multiple of 2 e.g. 16, 32 or 64
// 24LC256 -> 64 bytes
#define I2C_EEPROM_PAGESIZE 64

// TWI buffer needs max 2 bytes for address
#define I2C_TWIBUFFERSIZE  30

// to break blocking read/write
#define I2C_EEPROM_TIMEOUT  1000

// comment next line to keep lib small
#define I2C_EEPROM_EXTENDED

class I2C_eeprom
{
public:
    I2C_eeprom(uint8_t deviceAddress);

    int writeByte(uint16_t address, uint8_t value);
    int writeBlock(uint16_t address, uint8_t* buffer, uint16_t length);
    int setBlock(uint16_t address, uint8_t value, uint16_t length);

    uint8_t readByte(uint16_t address);
    uint16_t readBlock(uint16_t address, uint8_t* buffer, uint16_t length);

#ifdef I2C_EEPROM_EXTENDED
    uint8_t determineSize();
#endif

private:
    uint8_t _deviceAddress;
    uint32_t _lastWrite;  // for waitEEReady

    int _pageBlock(uint16_t address, uint8_t* buffer, uint16_t length, bool incrBuffer);
    int _WriteBlock(uint16_t address, uint8_t* buffer, uint8_t length);
    uint8_t _ReadBlock(uint16_t address, uint8_t* buffer, uint8_t length);

    void waitEEReady();
};

#endif
// END OF FILE
