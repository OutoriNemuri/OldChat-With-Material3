/*
 * ML-KEM-768 API 校验（在 CI 的 JVM 上运行，与应用代码使用**完全相同**的 BouncyCastle API）。
 *
 * 背景：加密通话最初按 `org.bouncycastle.pqc.crypto.mlkem.*` 的反射路径实现，
 * 而 1.78.1 里根本没有该包（只有老的 crystals.kyber），且带 ML-KEM 的版本把类放在
 * `org.bouncycastle.crypto.{params,generators,kems}` 下 → 运行期 ClassNotFoundException
 * → 静默降级 ECDH P-256（debug/release 都一样）。
 *
 * 这个程序把「库是否真能跑通、密钥/密文长度是否与 enigmaj 实测一致」变成构建期可验证的事实：
 *   ek 1184 B → base64 1580 字符（enigmaj 抓包实测 1580）
 *   ct 1088 B → base64 1452 字符（enigmaj 抓包实测 1452）
 *   ss   32 B（与抓包密钥 44 字符 base64 一致）
 *
 * 编译运行（需要 bcprov-jdk18on jar）：
 *   javac -cp bcprov.jar -d /tmp/kemcheck KemCheck.java
 *   java  -cp bcprov.jar:/tmp/kemcheck KemCheck
 *
 * 注意：这个文件放在 `.github/` 下而不是 `tools/` —— 仓库 `.gitignore` 里有 `/tools/`，
 * 放那儿不会被提交（第一次就踩了这个坑，CI 报 file not found）。
 */
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator;
import org.bouncycastle.crypto.kems.MLKEMExtractor;
import org.bouncycastle.crypto.kems.MLKEMGenerator;
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters;
import org.bouncycastle.crypto.params.MLKEMParameters;
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class KemCheck {

    public static void main(String[] args) {
        // 1) 生成 ML-KEM-768 密钥对
        MLKEMKeyPairGenerator kpg = new MLKEMKeyPairGenerator();
        kpg.init(new MLKEMKeyGenerationParameters(new SecureRandom(), MLKEMParameters.ml_kem_768));
        AsymmetricCipherKeyPair kp = kpg.generateKeyPair();

        byte[] ek = ((MLKEMPublicKeyParameters) kp.getPublic()).getEncoded();
        byte[] dk = ((MLKEMPrivateKeyParameters) kp.getPrivate()).getEncoded();

        // 2) 封装（响应方视角）/ 解封装（发起方视角）
        SecretWithEncapsulation encapsulated =
                new MLKEMGenerator(new SecureRandom()).generateEncapsulated(kp.getPublic());
        byte[] ct = encapsulated.getEncapsulation();
        byte[] ssEncap = encapsulated.getSecret();
        byte[] ssDecap = new MLKEMExtractor((MLKEMPrivateKeyParameters) kp.getPrivate())
                .extractSecret(ct);

        int ekB64 = Base64.getEncoder().encodeToString(ek).length();
        int ctB64 = Base64.getEncoder().encodeToString(ct).length();

        System.out.printf("ek=%d B (base64 %d)  dk=%d B  ct=%d B (base64 %d)  ss=%d B%n",
                ek.length, ekB64, dk.length, ct.length, ctB64, ssEncap.length);
        System.out.println("ss 一致: " + Arrays.equals(ssEncap, ssDecap));

        boolean ok = ek.length == 1184 && ct.length == 1088 && ssEncap.length == 32
                && ekB64 == 1580 && ctB64 == 1452
                && Arrays.equals(ssEncap, ssDecap);

        if (!ok) {
            System.err.println("FAIL: 与 enigmaj 实测长度不符，或封装/解封装结果不一致");
            System.exit(1);
        }
        System.out.println("OK: ML-KEM-768 可用，长度与 enigmaj 抓包一致（ek 1184/1580, ct 1088/1452, ss 32）");
    }
}
