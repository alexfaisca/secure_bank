package pt.ulisboa.ist.sirs.cryptology;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Operations {
  public static byte[] encryptData(
    SecretKey secretKey, byte[] message, byte[] iv
  ) throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException,
  BadPaddingException, InvalidAlgorithmParameterException {
    IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);

    Cipher cipher = Cipher.getInstance(Base.CIPHER_ALG);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);

    return cipher.doFinal(message);
  }

  public static byte[] decryptData(
    SecretKey secretKey, byte[] cipherText, byte[] iv
  ) throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException,
  BadPaddingException, InvalidAlgorithmParameterException {
    IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);

    Cipher cipher = Cipher.getInstance(Base.CIPHER_ALG);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);

    return cipher.doFinal(cipherText);
  }

  public static byte[] encryptDataAsymmetric(
    PublicKey secretKey, byte[] message
  ) throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException,
  BadPaddingException {
    Cipher cipher = Cipher.getInstance(Base.ASYMMETRIC_ALG);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
    return cipher.doFinal(message);
  }

  public static byte[] decryptDataAsymmetric(
    PrivateKey secretKey, byte[] cipherText
  ) throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException,
  BadPaddingException {
    Cipher cipher = Cipher.getInstance(Base.ASYMMETRIC_ALG);
    cipher.init(Cipher.DECRYPT_MODE, secretKey);
    return cipher.doFinal(cipherText);
  }

  public static byte[] hash(
    byte[] message
  ) throws NoSuchAlgorithmException {
    final MessageDigest messageDigest = MessageDigest.getInstance(Base.HASH_ALG);
    return messageDigest.digest(message);
  }

  public static byte[] messageSignature(
    PrivateKey privateKey, byte[] message
  ) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance(Base.HASH_ALG).digest(message);

    Signature signature = Signature.getInstance(Base.SIGNATURE_ALG);
    signature.initSign(privateKey);
    signature.update(digest);

    return signature.sign();
  }

  public static boolean messageValidation(
    PublicKey publicKey, byte[] message, byte[] messageSignature
  ) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance(Base.HASH_ALG).digest(message);

    Signature signature = Signature.getInstance(Base.SIGNATURE_ALG);
    signature.initVerify(publicKey);
    signature.update(digest);

    return signature.verify(messageSignature);
  }

  public static byte[] generateSessionKey() {
    try {
      KeyGenerator keyGenerator = KeyGenerator.getInstance(Base.SYMMETRIC_ALG);
      keyGenerator.init(Base.SYMMETRIC_KEY_SIZE * 8); // Get number of bits in key (1 byte = 8 bits)
      SecretKey symmetricKey = keyGenerator.generateKey();
      return symmetricKey.getEncoded();
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
    return null;
  }

  public static byte[] generateIV(
    Integer id, byte[] secretKey, String secret
  ) {
    try {
      return Arrays.copyOf(
        encryptData(
          new SecretKeySpec(secretKey, Base.SYMMETRIC_ALG),
          MessageDigest.getInstance(Base.HASH_ALG).digest(secret.getBytes()),
          Arrays.copyOf(
            MessageDigest.getInstance(Base.HASH_ALG).digest(ByteBuffer.allocate(Integer.BYTES).putInt(id).array()),
            Base.IV_SIZE)),
              Base.IV_SIZE);
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
    return null;
  }
}
