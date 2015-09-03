jssmp
=====

jssmp is a Java implementation of the
[Stupid-Simple Messaging Protocol](https://github.com/aerofs/ssmp).

License
-------

BSD 3-clause, see accompanying LICENSE file.


Dependencies
------------

  - JDK 1.8 or higher
  - [netty](http://netty.io) 3.10+
  - [slf4j](http://slf4j.org) 1.7+
  - [guava](https://github.com/google/guava) 17+


Example usage
-------------

## Client

```
// given: SSLEngine sslEngine;
SSMPClient c = new SSMPClient("localhost", 1234, new HashedWheelTimer(),
        new NioClientSocketChannelFactory(), () -> new SslHandler(sslEngine),
        e -> System.out.println("event: " + e));
c.connect(SSMPIdentifier.fromInternal("foo"),
        SSMPIdentifier.fromInternal("secret"), "NotARealSecret",
        new ConnectionListener() {
            @Override public void connected() { System.out.println("connected"); }
            @Override public void disconnected() { System.out.println("disconnected"); }
        });
SSMPResponse r = c.request(SSMPRequest.ucast(SSMPIdentifier.fromInternal("bar"),
        "Hello World!")).get();
System.out.println("response: " + r);
```

## Server

```
// given: SSLEngine sslEngine;
SSMPServer s = new SSMPServer(new InetSocketAddress("localhost", 1234),
        new HashedWheelTimer(), new NioServerSocketChannelFactory(),
        () -> new SslHandler(sslEngine),
        new Authenticator() {
            @Override public boolean authenticate(SSMPIdentifier id, SSMPIdentifier scheme, String cred) {
                return scheme.toString().equals("secret") && cred.equals("NotARealSecret");
            }
            @Override public ChannelBuffer unauthorized() {
                return ChannelBuffers.wrappedBuffer("401 secret\n".getBytes(StandardCharsets.US_ASCII));
            }
        });
s.start();
```

Notes
-----

The server implementation was written for in-process integration testing. It
did not go through the kind of extensive testing and optimization required
to be considered production-quality.

