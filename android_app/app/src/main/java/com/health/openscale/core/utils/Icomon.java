public class Icomon{
   //input: hex-code from scale advertisement
   public float decode(String hex){
      long dec = Long.parseLong(hex, 16);
      float result = dec ^ 0xc8a0a0;
      return result / 1000;
   }
   
   //input: the three bytes from scale advertisement
   public float decode(byte[] bArr){
      String hex = new String();
      for (byte b : bArr) {
         hex += String.format("%02X", b);
      }
      return decode(hex);
   }
}
