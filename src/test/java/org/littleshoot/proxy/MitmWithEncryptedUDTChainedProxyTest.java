package org.littleshoot.proxy;

import org.junit.jupiter.api.BeforeAll;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

import javax.net.ssl.SSLEngine;

import static org.littleshoot.proxy.TestUtils.disableOnMac;
import static org.littleshoot.proxy.TransportProtocol.UDT;

public final class MitmWithEncryptedUDTChainedProxyTest extends MitmWithChainedProxyTest {
    private final SslEngineSource sslEngineSource = new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks");

    @BeforeAll
    static void beforeClass() {
        disableOnMac();
    }

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(UDT)
                .withSslEngineSource(sslEngineSource);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public TransportProtocol getTransportProtocol() {
                return UDT;
            }

            @Override
            public boolean requiresEncryption() {
                return true;
            }

            @Override
            public SSLEngine newSslEngine() {
                return sslEngineSource.newSslEngine();
            }
        };
    }
}
