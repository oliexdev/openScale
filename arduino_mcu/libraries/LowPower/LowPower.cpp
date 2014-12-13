/*******************************************************************************
* LowPower Library
* Version: 1.30
* Date: 22-05-2013
* Company: Rocket Scream Electronics
* Website: www.rocketscream.com
*
* This is a lightweight low power library for Arduino. Please check our wiki 
* (www.rocketscream.com/wiki) for more information on using this piece of 
* library.
*
* This library is licensed under Creative Commons Attribution-ShareAlike 3.0 
* Unported License.
*
* Revision  Description
* ========  ===========
* 1.30			Added support for ATMega168, ATMega2560, ATMega1280 & ATMega32U4.
*						Tested to work with Arduino IDE 1.0.1 - 1.0.4.
* 1.20			Remove typo error in idle method for checking whether Timer 0 was 
*						turned off.
*						Remove dependecy on WProgram.h which is not required.
*						Tested to work with Arduino IDE 1.0.
* 1.10			Added #ifndef for sleep_bod_disable() for compatibility with future
*						Arduino IDE release.
* 1.00      Initial public release.
*******************************************************************************/
#include <avr/sleep.h>
#include <avr/wdt.h>
#include <avr/power.h>
#include <avr/interrupt.h>
#include "LowPower.h"

// Only Pico Power devices can change BOD settings through software
#if defined __AVR_ATmega328P__
#ifndef sleep_bod_disable
#define sleep_bod_disable() 										\
do { 																\
  unsigned char tempreg; 													\
  __asm__ __volatile__("in %[tempreg], %[mcucr]" "\n\t" 			\
                       "ori %[tempreg], %[bods_bodse]" "\n\t" 		\
                       "out %[mcucr], %[tempreg]" "\n\t" 			\
                       "andi %[tempreg], %[not_bodse]" "\n\t" 		\
                       "out %[mcucr], %[tempreg]" 					\
                       : [tempreg] "=&d" (tempreg) 					\
                       : [mcucr] "I" _SFR_IO_ADDR(MCUCR), 			\
                         [bods_bodse] "i" (_BV(BODS) | _BV(BODSE)), \
                         [not_bodse] "i" (~_BV(BODSE))); 			\
} while (0)
#endif
#endif

#define	lowPowerBodOn(mode)	\
do { 						\
      set_sleep_mode(mode); \
      cli();				\
      sleep_enable();		\
      sei();				\
      sleep_cpu();			\
      sleep_disable();		\
      sei();				\
} while (0);

// Only Pico Power devices can change BOD settings through software
#if defined __AVR_ATmega328P__
#define	lowPowerBodOff(mode)\
do { 						\
      set_sleep_mode(mode); \
      cli();				\
      sleep_enable();		\
			sleep_bod_disable(); \
      sei();				\
      sleep_cpu();			\
      sleep_disable();		\
      sei();				\
} while (0);
#endif

// Some macros is still missing from AVR GCC distribution for ATmega32U4
#if defined __AVR_ATmega32U4__
	// Timer 4 PRR bit is currently not defined in iom32u4.h 
	#ifndef PRTIM4
		#define PRTIM4 4
	#endif

	// Timer 4 power reduction macro is not defined currently in power.h
	#ifndef power_timer4_disable
		#define power_timer4_disable()	(PRR1 |= (uint8_t)(1 << PRTIM4)) 									
	#endif

	#ifndef power_timer4_enable
		#define power_timer4_enable()		(PRR1 &= (uint8_t)~(1 << PRTIM4))							
	#endif
#endif
  
/*******************************************************************************
* Name: idle
* Description: Putting ATmega328P/168 into idle state. Please make sure you 
*			         understand the implication and result of disabling module.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control:
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. timer2		Timer 2 module disable control:
*				(a) TIMER2_OFF - Turn off Timer 2 module
*				(b) TIMER2_ON - Leave Timer 2 module in its default state
*
* 4. timer1		Timer 1 module disable control:
*				(a) TIMER1_OFF - Turn off Timer 1 module
*				(b) TIMER1_ON - Leave Timer 1 module in its default state
*
* 5. timer0		Timer 0 module disable control:
*				(a) TIMER0_OFF - Turn off Timer 0 module
*				(b) TIMER0_ON - Leave Timer 0 module in its default state
*
* 6. spi		SPI module disable control:
*				(a) SPI_OFF - Turn off SPI module
*				(b) SPI_ON - Leave SPI module in its default state
*
* 7. usart0		USART0 module disable control:
*				(a) USART0_OFF - Turn off USART0  module
*				(b) USART0_ON - Leave USART0 module in its default state
*
* 8. twi		TWI module disable control:
*				(a) TWI_OFF - Turn off TWI module
*				(b) TWI_ON - Leave TWI module in its default state
*
*******************************************************************************/
#if defined (__AVR_ATmega328P__) || defined (__AVR_ATmega168__)
void	LowPowerClass::idle(period_t period, adc_t adc, timer2_t timer2, 
							timer1_t timer1, timer0_t timer0,
							spi_t spi, usart0_t usart0,	twi_t twi)
{
	// Temporary clock source variable 
	unsigned char clockSource = 0;
	
	if (timer2 == TIMER2_OFF)
	{
		if (TCCR2B & CS22) clockSource |= (1 << CS22);
		if (TCCR2B & CS21) clockSource |= (1 << CS21);
		if (TCCR2B & CS20) clockSource |= (1 << CS20);
	
		// Remove the clock source to shutdown Timer2
		TCCR2B &= ~(1 << CS22);
		TCCR2B &= ~(1 << CS21);
		TCCR2B &= ~(1 << CS20);
		
		power_timer2_disable();
	}
	
	if (adc == ADC_OFF)	
	{
		ADCSRA &= ~(1 << ADEN);
		power_adc_disable();
	}
	
	if (timer1 == TIMER1_OFF)	power_timer1_disable();	
	if (timer0 == TIMER0_OFF)	power_timer0_disable();	
	if (spi == SPI_OFF)			power_spi_disable();
	if (usart0 == USART0_OFF)	power_usart0_disable();
	if (twi == TWI_OFF)			power_twi_disable();
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	lowPowerBodOn(SLEEP_MODE_IDLE);
	
	if (adc == ADC_OFF)
	{
		power_adc_enable();
		ADCSRA |= (1 << ADEN);
	}
	
	if (timer2 == TIMER2_OFF)
	{
		if (clockSource & CS22) TCCR2B |= (1 << CS22);
		if (clockSource & CS21) TCCR2B |= (1 << CS21);
		if (clockSource & CS20) TCCR2B |= (1 << CS20);
		
		power_timer2_enable();
	}
	
	if (timer1 == TIMER1_OFF)	power_timer1_enable();	
	if (timer0 == TIMER0_OFF)	power_timer0_enable();	
	if (spi == SPI_OFF)			power_spi_enable();
	if (usart0 == USART0_OFF)	power_usart0_enable();
	if (twi == TWI_OFF)			power_twi_enable();
}
#endif

/*******************************************************************************
* Name: idle
* Description: Putting ATmega32U4 into idle state. Please make sure you 
*			         understand the implication and result of disabling module.
*							 Take note that Timer 2 is not available and USART0
*						   is replaced with USART1 on ATmega32U4.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control:
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. timer4		Timer 4 module disable control:
*				(a) TIMER4_OFF - Turn off Timer 4 module
*				(b) TIMER4_ON - Leave Timer 4 module in its default state
*
* 4. timer3		Timer 3 module disable control:
*				(a) TIMER3_OFF - Turn off Timer 3 module
*				(b) TIMER3_ON - Leave Timer 3 module in its default state
*
* 5. timer1		Timer 1 module disable control:
*				(a) TIMER1_OFF - Turn off Timer 1 module
*				(b) TIMER1_ON - Leave Timer 1 module in its default state
*
* 6. timer0		Timer 0 module disable control:
*				(a) TIMER0_OFF - Turn off Timer 0 module
*				(b) TIMER0_ON - Leave Timer 0 module in its default state
*
* 7. spi		SPI module disable control:
*				(a) SPI_OFF - Turn off SPI module
*				(b) SPI_ON - Leave SPI module in its default state
*
* 8. usart1		USART1 module disable control:
*				(a) USART1_OFF - Turn off USART1  module
*				(b) USART1_ON - Leave USART1 module in its default state
*
* 9. twi		TWI module disable control:
*				(a) TWI_OFF - Turn off TWI module
*				(b) TWI_ON - Leave TWI module in its default state
*
* 10.usb		USB module disable control:
*				(a) USB_OFF - Turn off USB module
*				(b) USB_ON - Leave USB module in its default state
*******************************************************************************/
#if defined __AVR_ATmega32U4__
void	LowPowerClass::idle(period_t period, adc_t adc, 
													timer4_t timer4, timer3_t timer3, 
							            timer1_t timer1, timer0_t timer0,
							            spi_t spi, usart1_t usart1,	twi_t twi, usb_t usb)
{
	if (adc == ADC_OFF)	
	{
		ADCSRA &= ~(1 << ADEN);
		power_adc_disable();
	}

	if (timer4 == TIMER4_OFF)	power_timer4_disable();	
	if (timer3 == TIMER3_OFF)	power_timer3_disable();		
	if (timer1 == TIMER1_OFF)	power_timer1_disable();	
	if (timer0 == TIMER0_OFF)	power_timer0_disable();	
	if (spi == SPI_OFF)				power_spi_disable();
	if (usart1 == USART1_OFF)	power_usart1_disable();
	if (twi == TWI_OFF)				power_twi_disable();
	if (usb == USB_OFF)				power_usb_disable();
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	lowPowerBodOn(SLEEP_MODE_IDLE);
	
	if (adc == ADC_OFF)
	{
		power_adc_enable();
		ADCSRA |= (1 << ADEN);
	}

	if (timer4 == TIMER4_OFF)	power_timer4_enable();	
	if (timer3 == TIMER3_OFF)	power_timer3_enable();	
	if (timer1 == TIMER1_OFF)	power_timer1_enable();	
	if (timer0 == TIMER0_OFF)	power_timer0_enable();	
	if (spi == SPI_OFF)				power_spi_enable();
	if (usart1 == USART1_OFF)	power_usart1_enable();
	if (twi == TWI_OFF)				power_twi_enable();
	if (usb == USB_OFF)				power_usb_enable();
}
#endif

/*******************************************************************************
* Name: idle
* Description: Putting ATmega2560 & ATmega1280 into idle state. Please make sure 
*			         you understand the implication and result of disabling module.
*							 Take note that extra Timer 5, 4, 3 compared to an ATmega328P/168.
*							 Also take note that extra USART 3, 2, 1 compared to an 
*							 ATmega328P/168.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control:
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. timer5		Timer 5 module disable control:
*				(a) TIMER5_OFF - Turn off Timer 5 module
*				(b) TIMER5_ON - Leave Timer 5 module in its default state
*
* 4. timer4		Timer 4 module disable control:
*				(a) TIMER4_OFF - Turn off Timer 4 module
*				(b) TIMER4_ON - Leave Timer 4 module in its default state
*
* 5. timer3		Timer 3 module disable control:
*				(a) TIMER3_OFF - Turn off Timer 3 module
*				(b) TIMER3_ON - Leave Timer 3 module in its default state
*
* 6. timer2		Timer 2 module disable control:
*				(a) TIMER2_OFF - Turn off Timer 2 module
*				(b) TIMER2_ON - Leave Timer 2 module in its default state
*
* 7. timer1		Timer 1 module disable control:
*				(a) TIMER1_OFF - Turn off Timer 1 module
*				(b) TIMER1_ON - Leave Timer 1 module in its default state
*
* 8. timer0		Timer 0 module disable control:
*				(a) TIMER0_OFF - Turn off Timer 0 module
*				(b) TIMER0_ON - Leave Timer 0 module in its default state
*
* 9. spi		SPI module disable control:
*				(a) SPI_OFF - Turn off SPI module
*				(b) SPI_ON - Leave SPI module in its default state
*
* 10.usart3		USART3 module disable control:
*				(a) USART3_OFF - Turn off USART3  module
*				(b) USART3_ON - Leave USART3 module in its default state
*
* 11.usart2		USART2 module disable control:
*				(a) USART2_OFF - Turn off USART2  module
*				(b) USART2_ON - Leave USART2 module in its default state
*
* 12.usart1		USART1 module disable control:
*				(a) USART1_OFF - Turn off USART1  module
*				(b) USART1_ON - Leave USART1 module in its default state
*
* 13.usart0		USART0 module disable control:
*				(a) USART0_OFF - Turn off USART0  module
*				(b) USART0_ON - Leave USART0 module in its default state
*
* 14.twi		TWI module disable control:
*				(a) TWI_OFF - Turn off TWI module
*				(b) TWI_ON - Leave TWI module in its default state
*
*******************************************************************************/
#if defined (__AVR_ATmega2560__) || defined (__AVR_ATmega1280__)
void	LowPowerClass::idle(period_t period, adc_t adc, timer5_t timer5, 
					                timer4_t timer4, timer3_t timer3, timer2_t timer2, 
													timer1_t timer1, timer0_t timer0, spi_t spi, 
													usart3_t usart3, usart2_t usart2, usart1_t usart1, 
			                    usart0_t usart0, twi_t twi)
{
	// Temporary clock source variable 
	unsigned char clockSource = 0;
	
	if (timer2 == TIMER2_OFF)
	{
		if (TCCR2B & CS22) clockSource |= (1 << CS22);
		if (TCCR2B & CS21) clockSource |= (1 << CS21);
		if (TCCR2B & CS20) clockSource |= (1 << CS20);
	
		// Remove the clock source to shutdown Timer2
		TCCR2B &= ~(1 << CS22);
		TCCR2B &= ~(1 << CS21);
		TCCR2B &= ~(1 << CS20);
		
		power_timer2_disable();
	}
	
	if (adc == ADC_OFF)	
	{
		ADCSRA &= ~(1 << ADEN);
		power_adc_disable();
	}
	
	if (timer5 == TIMER5_OFF)	power_timer5_disable();	
	if (timer4 == TIMER4_OFF)	power_timer4_disable();	
	if (timer3 == TIMER3_OFF)	power_timer3_disable();	
	if (timer1 == TIMER1_OFF)	power_timer1_disable();	
	if (timer0 == TIMER0_OFF)	power_timer0_disable();	
	if (spi == SPI_OFF)			  power_spi_disable();
	if (usart3 == USART3_OFF)	power_usart3_disable();
	if (usart2 == USART2_OFF)	power_usart2_disable();
	if (usart1 == USART1_OFF)	power_usart1_disable();
	if (usart0 == USART0_OFF)	power_usart0_disable();
	if (twi == TWI_OFF)			  power_twi_disable();
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	lowPowerBodOn(SLEEP_MODE_IDLE);
	
	if (adc == ADC_OFF)
	{
		power_adc_enable();
		ADCSRA |= (1 << ADEN);
	}
	
	if (timer2 == TIMER2_OFF)
	{
		if (clockSource & CS22) TCCR2B |= (1 << CS22);
		if (clockSource & CS21) TCCR2B |= (1 << CS21);
		if (clockSource & CS20) TCCR2B |= (1 << CS20);
		
		power_timer2_enable();
	}

	if (timer5 == TIMER5_OFF)	power_timer5_enable();	
	if (timer4 == TIMER4_OFF)	power_timer4_enable();		
	if (timer3 == TIMER3_OFF)	power_timer3_enable();	
	if (timer1 == TIMER1_OFF)	power_timer1_enable();	
	if (timer0 == TIMER0_OFF)	power_timer0_enable();	
	if (spi == SPI_OFF)			  power_spi_enable();
	if (usart3 == USART3_OFF)	power_usart3_enable();
	if (usart2 == USART2_OFF)	power_usart2_enable();
	if (usart1 == USART1_OFF)	power_usart1_enable();
	if (usart0 == USART0_OFF)	power_usart0_enable();
	if (twi == TWI_OFF)			  power_twi_enable();
}
#endif

/*******************************************************************************
* Name: adcNoiseReduction
* Description: Putting microcontroller into ADC noise reduction state. This is
*			         a very useful state when using the ADC to achieve best and low 
*              noise signal.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control. Turning off the ADC module is
*				basically removing the purpose of this low power mode.
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. timer2		Timer 2 module disable control:
*				(a) TIMER2_OFF - Turn off Timer 2 module
*				(b) TIMER2_ON - Leave Timer 2 module in its default state
*
*******************************************************************************/
void	LowPowerClass::adcNoiseReduction(period_t period, adc_t adc, 
										 timer2_t timer2)
{
	// Temporary clock source variable 
	unsigned char clockSource = 0;
	
	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (TCCR2B & CS22) clockSource |= (1 << CS22);
		if (TCCR2B & CS21) clockSource |= (1 << CS21);
		if (TCCR2B & CS20) clockSource |= (1 << CS20);
	
		// Remove the clock source to shutdown Timer2
		TCCR2B &= ~(1 << CS22);
		TCCR2B &= ~(1 << CS21);
		TCCR2B &= ~(1 << CS20);
	}
	#endif
	
	if (adc == ADC_OFF)	ADCSRA &= ~(1 << ADEN);
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	lowPowerBodOn(SLEEP_MODE_ADC);
	
	if (adc == ADC_OFF) ADCSRA |= (1 << ADEN);
	
	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (clockSource & CS22) TCCR2B |= (1 << CS22);
		if (clockSource & CS21) TCCR2B |= (1 << CS21);
		if (clockSource & CS20) TCCR2B |= (1 << CS20);
		
	}
	#endif
}

/*******************************************************************************
* Name: powerDown
* Description: Putting microcontroller into power down state. This is
*			         the lowest current consumption state. Use this together with 
*			         external pin interrupt to wake up through external event 
*			         triggering (example: RTC clockout pin, SD card detect pin).
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control. Turning off the ADC module is
*				basically removing the purpose of this low power mode.
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. bod		Brown Out Detector (BOD) module disable control:
*				(a) BOD_OFF - Turn off BOD module
*				(b) BOD_ON - Leave BOD module in its default state
*
*******************************************************************************/
void	LowPowerClass::powerDown(period_t period, adc_t adc, bod_t bod)
{
	if (adc == ADC_OFF)	ADCSRA &= ~(1 << ADEN);
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	if (bod == BOD_OFF)	
	{
		#if defined __AVR_ATmega328P__
			lowPowerBodOff(SLEEP_MODE_PWR_DOWN);
		#else
			lowPowerBodOn(SLEEP_MODE_PWR_DOWN);
		#endif
	}
	else	
	{
		lowPowerBodOn(SLEEP_MODE_PWR_DOWN);
	}
	
	if (adc == ADC_OFF) ADCSRA |= (1 << ADEN);
}

/*******************************************************************************
* Name: powerSave
* Description: Putting microcontroller into power save state. This is
*			   the lowest current consumption state after power down. 
*			   Use this state together with an external 32.768 kHz crystal (but
*			   8/16 MHz crystal/resonator need to be removed) to provide an
*			   asynchronous clock source to Timer 2. Please take note that 
*			   Timer 2 is also used by the Arduino core for PWM operation. 
*			   Please refer to wiring.c for explanation. Removal of the external
*			   8/16 MHz crystal/resonator requires the microcontroller to run
*			   on its internal RC oscillator which is not so accurate for time
*			   critical operation.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control. Turning off the ADC module is
*				basically removing the purpose of this low power mode.
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. bod		Brown Out Detector (BOD) module disable control:
*				(a) BOD_OFF - Turn off BOD module
*				(b) BOD_ON - Leave BOD module in its default state
*
* 4. timer2		Timer 2 module disable control:
*				(a) TIMER2_OFF - Turn off Timer 2 module
*				(b) TIMER2_ON - Leave Timer 2 module in its default state
*
*******************************************************************************/
void	LowPowerClass::powerSave(period_t period, adc_t adc, bod_t bod, 
							     timer2_t timer2)
{
	// Temporary clock source variable 
	unsigned char clockSource = 0;

	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (TCCR2B & CS22) clockSource |= (1 << CS22);
		if (TCCR2B & CS21) clockSource |= (1 << CS21);
		if (TCCR2B & CS20) clockSource |= (1 << CS20);
	
		// Remove the clock source to shutdown Timer2
		TCCR2B &= ~(1 << CS22);
		TCCR2B &= ~(1 << CS21);
		TCCR2B &= ~(1 << CS20);
	}
	#endif
	
	if (adc == ADC_OFF)	ADCSRA &= ~(1 << ADEN);
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	if (bod == BOD_OFF)	
	{
		#if defined __AVR_ATmega328P__
			lowPowerBodOff(SLEEP_MODE_PWR_SAVE);
		#else
			lowPowerBodOn(SLEEP_MODE_PWR_SAVE);
		#endif
	}
	else
	{
		lowPowerBodOn(SLEEP_MODE_PWR_SAVE);
	}
	
	if (adc == ADC_OFF) ADCSRA |= (1 << ADEN);
	
	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (clockSource & CS22) TCCR2B |= (1 << CS22);
		if (clockSource & CS21) TCCR2B |= (1 << CS21);
		if (clockSource & CS20) TCCR2B |= (1 << CS20);
	}
	#endif
}

/*******************************************************************************
* Name: powerStandby
* Description: Putting microcontroller into power standby state. 
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control. Turning off the ADC module is
*				basically removing the purpose of this low power mode.
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. bod		Brown Out Detector (BOD) module disable control:
*				(a) BOD_OFF - Turn off BOD module
*				(b) BOD_ON - Leave BOD module in its default state
*
*******************************************************************************/
void	LowPowerClass::powerStandby(period_t period, adc_t adc, bod_t bod)
{
	if (adc == ADC_OFF)	ADCSRA &= ~(1 << ADEN);
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	
	if (bod == BOD_OFF)	
	{
		#if defined __AVR_ATmega328P__
			lowPowerBodOff(SLEEP_MODE_STANDBY);
		#else
			lowPowerBodOn(SLEEP_MODE_STANDBY);
		#endif
	}
	else
	{
		lowPowerBodOn(SLEEP_MODE_STANDBY);
	}
	
	if (adc == ADC_OFF) ADCSRA |= (1 << ADEN);
}

/*******************************************************************************
* Name: powerExtStandby
* Description: Putting microcontroller into power extended standby state. This 
*			   is different from the power standby state as it has the 
*			   capability to run Timer 2 asynchronously.
*
* Argument  	Description
* =========  	===========
* 1. period   Duration of low power mode. Use SLEEP_FOREVER to use other wake
*				up resource:
*				(a) SLEEP_15MS - 15 ms sleep
*				(b) SLEEP_30MS - 30 ms sleep
*				(c) SLEEP_60MS - 60 ms sleep
*				(d) SLEEP_120MS - 120 ms sleep
*				(e) SLEEP_250MS - 250 ms sleep
*				(f) SLEEP_500MS - 500 ms sleep
*				(g) SLEEP_1S - 1 s sleep
*				(h) SLEEP_2S - 2 s sleep
*				(i) SLEEP_4S - 4 s sleep
*				(j) SLEEP_8S - 8 s sleep
*				(k) SLEEP_FOREVER - Sleep without waking up through WDT
*
* 2. adc		ADC module disable control.
*				(a) ADC_OFF - Turn off ADC module
*				(b) ADC_ON - Leave ADC module in its default state
*
* 3. bod		Brown Out Detector (BOD) module disable control:
*				(a) BOD_OFF - Turn off BOD module
*				(b) BOD_ON - Leave BOD module in its default state
*
* 4. timer2		Timer 2 module disable control:
*				(a) TIMER2_OFF - Turn off Timer 2 module
*				(b) TIMER2_ON - Leave Timer 2 module in its default state
*
*******************************************************************************/
void	LowPowerClass::powerExtStandby(period_t period, adc_t adc, bod_t bod, 
									   timer2_t timer2)
{
	// Temporary clock source variable 
	unsigned char clockSource = 0;
	
	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (TCCR2B & CS22) clockSource |= (1 << CS22);
		if (TCCR2B & CS21) clockSource |= (1 << CS21);
		if (TCCR2B & CS20) clockSource |= (1 << CS20);
	
		// Remove the clock source to shutdown Timer2
		TCCR2B &= ~(1 << CS22);
		TCCR2B &= ~(1 << CS21);
		TCCR2B &= ~(1 << CS20);
	}
	#endif
	
	if (adc == ADC_OFF)	ADCSRA &= ~(1 << ADEN);
	
	if (period != SLEEP_FOREVER)
	{
		wdt_enable(period);
		WDTCSR |= (1 << WDIE);	
	}
	if (bod == BOD_OFF)	
	{
		#if defined __AVR_ATmega328P__
			lowPowerBodOff(SLEEP_MODE_EXT_STANDBY);
		#else
			lowPowerBodOn(SLEEP_MODE_EXT_STANDBY);
		#endif
	}
	else	
	{
		lowPowerBodOn(SLEEP_MODE_EXT_STANDBY);
	}
		
	if (adc == ADC_OFF) ADCSRA |= (1 << ADEN);
	
	#if !defined(__AVR_ATmega32U4__)
	if (timer2 == TIMER2_OFF)
	{
		if (clockSource & CS22) TCCR2B |= (1 << CS22);
		if (clockSource & CS21) TCCR2B |= (1 << CS21);
		if (clockSource & CS20) TCCR2B |= (1 << CS20);	
	}
	#endif
}

/*******************************************************************************
* Name: ISR (WDT_vect)
* Description: Watchdog Timer interrupt service routine. This routine is 
*		           required to allow automatic WDIF and WDIE bit clearance in 
*			         hardware.
*
*******************************************************************************/
ISR (WDT_vect)
{
	// WDIE & WDIF is cleared in hardware upon entering this ISR
	wdt_disable();
}

LowPowerClass LowPower;