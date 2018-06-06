/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

#include <LowPower.h>
#include <RunningMedian.h>
#include <Wire.h>
#include <Time.h>
#include <DS3232RTC.h> 
#include <I2C_eeprom.h>

#define SEG_1_1 4
#define SEG_1_2 5
#define SEG_2_1 6
#define SEG_2_2 7
#define SEG_3_1 8
#define SEG_3_2 9
#define SEG_4_1 10
#define SEG_4_2 11
#define UP 12
#define C0 A0
#define C1 A1
#define C2 A2
#define C3 A3
#define WAKEUP_PIN 3
#define EXT_SWITCH_PIN 13

#define MAX_SAMPLE_SIZE 6
#define MAX_NO_ACTIVITY_CYCLES 32

I2C_eeprom eeprom(0x50);

char port_control;
char port_digital_pinA;
char port_digital_pinB;

int control_bit[4];

int seg_raw_1_1[4];
int seg_raw_1_2[4];
int seg_raw_2_1[4];
int seg_raw_2_2[4];
int seg_raw_3_1[4];
int seg_raw_3_2[4];
int seg_raw_4_1[4];
int seg_raw_4_2[4];

char seg_value_1;
char seg_value_2;
char seg_value_3;
char seg_value_4;

RunningMedian<char, MAX_SAMPLE_SIZE> seg_samples_1;
RunningMedian<char, MAX_SAMPLE_SIZE> seg_samples_2;
RunningMedian<char, MAX_SAMPLE_SIZE> seg_samples_3;
RunningMedian<char, MAX_SAMPLE_SIZE> seg_samples_4;

int sample_count = 0;

int no_activity_cycles = 0;

volatile boolean sleep_state = true;

int measured_user_id = -1;
int measured_weight = -1;
int measured_fat = -1;
int measured_water = -1;
int measured_muscle = -1;

typedef struct scale_data{
  byte user_id;
  int year;
  byte month;
  byte day;
  byte hour;
  byte minute;
  int weight;
  int fat;
  int water;
  int muscle;
  int checksum;
} __attribute__ ((packed)); // avoiding byte padding in this struct. Important for continuous writing/reading to/from eeprom!


void interrupt_handler()
{
  sleep_state = false;
}

void setup() {  
  Serial.begin(9600);

  pinMode(SEG_1_1, INPUT);
  pinMode(SEG_1_2, INPUT);
  pinMode(SEG_2_1, INPUT);
  pinMode(SEG_2_2, INPUT);
  pinMode(SEG_3_1, INPUT);
  pinMode(SEG_3_2, INPUT);
  pinMode(SEG_4_1, INPUT);
  pinMode(SEG_4_2, INPUT);
  pinMode(UP, OUTPUT);
  pinMode(C0, INPUT);
  pinMode(C1, INPUT);
  pinMode(C2, INPUT);
  pinMode(C3, INPUT);
  pinMode(WAKEUP_PIN, INPUT); 
  pinMode(EXT_SWITCH_PIN, OUTPUT);
  
  digitalWrite(EXT_SWITCH_PIN, HIGH);
}

void set_seg_raw(int cycle_n)
{
  seg_raw_1_1[cycle_n] = (port_digital_pinA & (1 << 4)) ? 1 : 0;
  seg_raw_1_2[cycle_n] = (port_digital_pinA & (1 << 5)) ? 1 : 0;
  seg_raw_2_1[cycle_n] = (port_digital_pinA & (1 << 6)) ? 1 : 0;
  seg_raw_2_2[cycle_n] = (port_digital_pinA & (1 << 7)) ? 1 : 0;
  seg_raw_3_1[cycle_n] = (port_digital_pinB & (1 << 0)) ? 1 : 0;
  seg_raw_3_2[cycle_n] = (port_digital_pinB & (1 << 1)) ? 1 : 0;
  seg_raw_4_1[cycle_n] = (port_digital_pinB & (1 << 2)) ? 1 : 0;
  seg_raw_4_2[cycle_n] = (port_digital_pinB & (1 << 3)) ? 1 : 0;
}

char decode_seg(int seg_x[4], int seg_y[4])
{
  boolean b = seg_x[0];
  boolean c = seg_x[1];
  boolean e = seg_x[2];
  boolean f = seg_x[3];
  boolean a = seg_y[0];
  boolean d = seg_y[1];
  boolean g = seg_y[2];
  boolean x = seg_y[3];

  if (!e && !c && !b && !f &&
    !g &&  !d && !a)
    return ' ';

  if (e && !c && b && f &&
    g &&  d && a)
    return '0';

  if (e && !c && b && !f &&
    !g &&  !d && !a)
    return '1';

  if (!e && c && b && f &&
    g &&  !d && a)
    return '2';

  if (e && c && b && f &&
    !g &&  !d && a)
    return '3';

  if (e && c && b && !f &&
    !g &&  d && !a)
    return '4';

  if (e && c && !b && f &&
    !g &&  d && a)
    return '5';

  if (e && c && !b && f &&
    g &&  d && a)
    return '6';

  if (e && !c && b && !f &&
    !g &&  !d && a)
    return '7';

  if (e && c && b && f &&
    g &&  d && a)
    return '8';

  if (e && c && b && f &&
    !g &&  d && a)
    return '9';

  if (!e && c && !b && !f &&
    !g &&  !d && !a)
    return '-';

  if (!e && c && b && !f &&
    g &&  d && a)
    return 'P';

  if (e && !c && b && !f &&
    g &&  d && a)
    return 'M';

  if (!e && c && !b && f &&
    g &&  d && a)
    return 'E';   

  if (!e && c && !b && !f &&
    g &&  d && a)
    return 'F';   

  if (e && c && b && !f &&
    g &&  d && !a)
    return 'H';   

  return -1;
}

void before_sleep_event()
{
   Serial.println("$I$ going to sleep in 3 seconds!");
  
  if (measured_user_id != -1 && measured_weight != -1 && measured_fat != -1 && measured_water != -1 && measured_muscle != -1) {
    write_scale_data(measured_user_id, measured_weight, measured_fat, measured_water, measured_muscle);
    delay(100);
  }
  
  send_scale_data();
  
  delay(3000);
  
  digitalWrite(EXT_SWITCH_PIN, LOW);   
}

void after_sleep_event()
{
  digitalWrite(EXT_SWITCH_PIN, HIGH);

  measured_user_id = -1;
  measured_weight = -1;
  measured_fat = -1;
  measured_water = -1;
  measured_muscle = -1;

  delay(4000);
  digitalWrite(UP, HIGH);
  delay(500);
  digitalWrite(UP, LOW);
  
  setSyncProvider(RTC.get);
  if (timeStatus() != timeSet) {
    Serial.println("$E$ Can't sync to RTC clock!");
  } else {
    Serial.println("$I$ Successful sync to RTC clock");
  }
  
  print_date_time();
  
  Serial.println("$I$ openScale MCU ready!");
}

void print_date_time()
{
  Serial.print("$I$ Time: ");
  Serial.print(hour());
  Serial.write(':');
  Serial.print(minute());
  Serial.write(':');
  Serial.print(second());
  Serial.print(" Date: ");
  Serial.print(day());
  Serial.write('/');
  Serial.print(month());
  Serial.write('/');
  Serial.print(year());
  Serial.println();
}

void check_display_activity()
{
  if (no_activity_cycles > MAX_NO_ACTIVITY_CYCLES)
  {
    sleep_state = true;
    no_activity_cycles = 0;
  }


  if (sleep_state == true)
  {        
    before_sleep_event();

    // Allow wake up pin to trigger interrupt on rising edge.
    attachInterrupt(1, interrupt_handler, RISING);

    // Enter power down state with ADC and BOD module disabled.
    // Wake up when wake up pin is rising.
    LowPower.powerDown(SLEEP_FOREVER, ADC_OFF, BOD_OFF); 

    // Disable external pin interrupt on wake up pin.
    detachInterrupt(1); 

    after_sleep_event();  
  } 
}

int calc_checksum(struct scale_data* wdata)
{
  int checksum = 0;
  
  checksum ^= wdata->user_id;
  checksum ^= wdata->year;
  checksum ^= wdata->month;
  checksum ^= wdata->day;
  checksum ^= wdata->hour;
  checksum ^= wdata->minute;
  checksum ^= (int)((float)wdata->weight / 10.0f);
  checksum ^= (int)((float)wdata->fat / 10.0f);
  checksum ^= (int)((float)wdata->water / 10.0f);
  checksum ^= (int)((float)wdata->muscle / 10.0f);

  return checksum;
}

void write_scale_data(int user_id, int weight, int fat, int water, int muscle)
{
  int data_size = 0;
  struct scale_data wdata;

  eeprom.readBlock(0, (uint8_t*)&data_size, sizeof(data_size));

  wdata.user_id = user_id;
  wdata.year = year();
  wdata.month = month();
  wdata.day = day();
  wdata.hour = hour();
  wdata.minute = minute();
  wdata.weight = weight;
  wdata.fat = fat;
  wdata.water = water;
  wdata.muscle = muscle;
  wdata.checksum = calc_checksum(&wdata);

  if (eeprom.writeBlock(sizeof(data_size)+data_size*sizeof(wdata), (uint8_t*)&wdata, sizeof(wdata)) != 0) {
    Serial.println("$E$ Error writing data to eeprom");
  }
  
  delay(100);
  data_size++;
    
  if (eeprom.writeBlock(0, (uint8_t*)&data_size, sizeof(data_size)) != 0) {
    Serial.println("$E$ Error writing data to eeprom");
  }
}

void send_scale_data()
{
  int data_size = 0;  
  struct scale_data wdata;

  eeprom.readBlock(0, (uint8_t*)&data_size, sizeof(data_size));
  
  Serial.print("$S$");
  Serial.println(data_size);
  
  for (int i=0; i < data_size; i++)
  {
    eeprom.readBlock(sizeof(data_size)+i*sizeof(wdata), (uint8_t*)&wdata, sizeof(wdata));

    if (wdata.checksum != calc_checksum(&wdata)) {
      Serial.print("$E$ Wrong Checksum for data ");
      Serial.print(i);
      Serial.println();
    }
    
      Serial.print("$D$");
      Serial.print(wdata.user_id);
      Serial.print(',');
      Serial.print(wdata.year);
      Serial.print(',');
      Serial.print(wdata.month);
      Serial.print(',');
      Serial.print(wdata.day);
      Serial.print(',');
      Serial.print(wdata.hour);
      Serial.print(',');
      Serial.print(wdata.minute);
      Serial.print(',');
      Serial.print((float)wdata.weight / 10.0f);
      Serial.print(',');
      Serial.print((float)wdata.fat  / 10.0f);
      Serial.print(',');
      Serial.print((float)wdata.water / 10.0f);
      Serial.print(',');
      Serial.print((float)wdata.muscle  / 10.0f);
      Serial.print(',');
      Serial.print(wdata.checksum);
      Serial.print('\n');
  }
  
  Serial.println("$F$ Scale data sent");
  
}

void clear_scale_data()
{
  int data_size = 0;
  eeprom.writeBlock(0, (uint8_t*)&data_size, sizeof(data_size));
}

void loop() 
{   
  check_display_activity(); 

  port_control = PINC;
  port_digital_pinA = PIND;
  port_digital_pinB = PINB;

  control_bit[0] = (port_control & (1 << 0)) ? 1 : 0;
  control_bit[1] = (port_control & (1 << 1)) ? 1 : 0;
  control_bit[2] = (port_control & (1 << 2)) ? 1 : 0;
  control_bit[3] = (port_control & (1 << 3)) ? 1 : 0;

  if (control_bit[0] == LOW && control_bit[1] == HIGH && control_bit[2] == HIGH && control_bit[3] == HIGH)
  {
    set_seg_raw(0);

  } 
  else if (control_bit[0] == HIGH && control_bit[1] == LOW && control_bit[2] == HIGH && control_bit[3] == HIGH)
  {
    set_seg_raw(1);

  } 
  else if (control_bit[0] == HIGH && control_bit[1] == HIGH && control_bit[2] == LOW && control_bit[3] == HIGH)
  {
    set_seg_raw(2);

  } 
  else if (control_bit[0] == HIGH && control_bit[1] == HIGH && control_bit[2] == HIGH && control_bit[3] == LOW)
  { 
    set_seg_raw(3);

  } 
  else if (control_bit[0] == HIGH && control_bit[1] == HIGH && control_bit[2] == HIGH && control_bit[3] == HIGH)
  {      
    no_activity_cycles++;
  }

  seg_value_1 = decode_seg(seg_raw_1_1, seg_raw_1_2);
  seg_value_2 = decode_seg(seg_raw_2_1, seg_raw_2_2);
  seg_value_3 = decode_seg(seg_raw_3_1, seg_raw_3_2);
  seg_value_4 = decode_seg(seg_raw_4_1, seg_raw_4_2);

  if (seg_value_1 != -1 && seg_value_2 != -1 && seg_value_3 != -1 && seg_value_4 != -1)
  {
    seg_samples_1.add(seg_value_1);
    seg_samples_2.add(seg_value_2);
    seg_samples_3.add(seg_value_3);
    seg_samples_4.add(seg_value_4);

    sample_count++;
  }


  if (sample_count > MAX_SAMPLE_SIZE)
  {
    seg_samples_1.getMedian(seg_value_1);
    seg_samples_2.getMedian(seg_value_2);
    seg_samples_3.getMedian(seg_value_3);
    seg_samples_4.getMedian(seg_value_4);
   
     if (seg_value_4 == ' ' || seg_value_4 == '1'  || seg_value_4 == '2') {
       measured_weight = char_to_int(seg_value_1) + char_to_int(seg_value_2)*10 + char_to_int(seg_value_3)*100;
       
       if (seg_value_4 == '1'  || seg_value_4 == '2') {
         measured_weight += char_to_int(seg_value_4)*1000;
       }
     }
   
     if (seg_value_4 == 'F') {
       measured_fat = char_to_int(seg_value_1) + char_to_int(seg_value_2)*10 + char_to_int(seg_value_3)*100;
     }
     
     if (seg_value_4 == 'H') {
       measured_water = char_to_int(seg_value_1) + char_to_int(seg_value_2)*10 + char_to_int(seg_value_3)*100;
     }
     
     if (seg_value_4 == 'M') {
       measured_muscle = char_to_int(seg_value_1) + char_to_int(seg_value_2)*10 + char_to_int(seg_value_3)*100;
     }
     
     if (seg_value_4 == 'P') {
       measured_user_id = char_to_int(seg_value_1) + char_to_int(seg_value_2)*10;
     }

    sample_count = 0; 
  }

  delay(10);


   if (Serial.available() > 0)
   {
     char command = Serial.read();
     
     switch(command)
     {
       case '0':
       Serial.println("$I$ openScale MCU Version 1.1");
       break;
       case '1':
       Serial.println("$I$ Sending scale data!");
       send_scale_data();
       break;
       case '2':
       set_rtc_time();
       break;
       case '3':
       Serial.println("$I$ Print RTC Time");
       print_date_time();
       break;
       case '9':
       clear_scale_data();
       Serial.println("$I$ Scale data cleared!");
       break;
     }
   }
}

void set_rtc_time() {
    static time_t tLast;
    time_t t;
    tmElements_t tm;

    setSyncProvider(RTC.get);

    boolean param_finished = false;

    while (!param_finished) {
      //check for input to set the RTC, minimum length is 12, i.e. yy,m,d,h,m,s
      if (Serial.available() >= 12) {
          //note that the tmElements_t Year member is an offset from 1970,
          //but the RTC wants the last two digits of the calendar year.
          //use the convenience macros from Time.h to do the conversions.
          int y = Serial.parseInt();
          if (y >= 100 && y < 1000)
              Serial.println("$E$ Error: Year must be two digits or four digits!");
          else {
              if (y >= 1000)
                  tm.Year = CalendarYrToTm(y);
              else    //(y < 100)
                  tm.Year = y2kYearToTm(y);
              tm.Month = Serial.parseInt();
              tm.Day = Serial.parseInt();
              tm.Hour = Serial.parseInt();
              tm.Minute = Serial.parseInt();
              tm.Second = Serial.parseInt();
              t = makeTime(tm);
              RTC.set(t);        //use the time_t value to ensure correct weekday is set
              setTime(t);        
              Serial.println("$I$ RTC set to: ");
              print_date_time();
              //dump any extraneous input
              while (Serial.available() > 0) Serial.read();
              
              param_finished = true;
          }
      }
  }
}

int char_to_int(char c)
{
  if (c == ' ')
    return 0;
  
  return (c - '0');
}

void print_debug_output()
{
  Serial.print("Debug output\n");
  Serial.print("-----------------------------------\n");
  Serial.print("\nSeg 1\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_1_1[i]);
  }  
  Serial.print("\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_1_2[i]);
  }

  Serial.print("\nSeg 2\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_2_1[i]);
  }
  Serial.print("\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_2_2[i]);
  } 

  Serial.print("\nSeg 3\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_3_1[i]);
  } 
  Serial.print("\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_3_2[i]);
  } 

  Serial.print("\nSeg 4\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_4_1[i]);
  } 
  Serial.print("\n");
  for (int i=0; i<4; i++)
  {
    Serial.print(seg_raw_4_2[i]);
  }

  Serial.print("\n-----------------------------------\n");
}
