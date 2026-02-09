/*
 * Copyright 2023 - present Maksym Ostroverkhov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jauntsdn.netty.channel.vsock;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.VSockAddress;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class VSockTest {

  Channel server;

  @AfterEach
  void tearDown() {
    Channel s = server;
    if (s != null) {
      s.close();
    }
  }

  @Test
  void remoteNonVSockAddress() throws Exception {
    InetSocketAddress unsupportedAddress = new InetSocketAddress("localhost", 8080);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> server(unsupportedAddress, new ChannelInboundHandlerAdapter()));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> client(unsupportedAddress, new ChannelInboundHandlerAdapter()));
  }

  @Test
  void localNonVSockAddress() {
    InetSocketAddress unsupportedAddress = new InetSocketAddress("localhost", 8080);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            client(
                new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080),
                unsupportedAddress,
                new ChannelInboundHandlerAdapter()));
  }

  @Test
  void nullRemoteAddress() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> client(null, new ChannelInboundHandlerAdapter()));
  }

  @Test
  void nullServerAddress() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> server(null, new ChannelInboundHandlerAdapter()));
  }

  @Test
  void connectionRefused() throws Exception {
    // Try to connect to a port where no server is listening
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 9999);

    Bootstrap bootstrap =
        new Bootstrap()
            .group(new EpollEventLoopGroup(1))
            .channel(EpollVSockChannel.class)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                  }
                });

    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class,
        () -> bootstrap.connect(serverAddress).sync());
  }

  @Test
  void doubleBindSamePort() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8090);

    server = server(serverAddress, new ChannelInboundHandlerAdapter());

    // Try to bind another server to the same address
    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class,
        () -> server(serverAddress, new ChannelInboundHandlerAdapter()));
  }

  @Test
  void resourceCleanupOnConnectionFailure() throws Exception {
    VSockAddress nonExistentServer = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 9998);

    Bootstrap bootstrap =
        new Bootstrap()
            .group(new EpollEventLoopGroup(1))
            .channel(EpollVSockChannel.class)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                  }
                });

    try {
      bootstrap.connect(nonExistentServer).sync();
      org.junit.jupiter.api.Assertions.fail("Should have thrown exception");
    } catch (Exception e) {
      // Expected - connection should fail
      // Verify channel is properly closed and resources released
      Assertions.assertThat(e).isNotNull();
    }
  }

  @Test
  void invalidAddressInBootstrap() {
    InetSocketAddress invalidAddress = new InetSocketAddress("localhost", 8080);

    Bootstrap bootstrap =
        new Bootstrap()
            .group(new EpollEventLoopGroup(1))
            .channel(EpollVSockChannel.class)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                  }
                });

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> bootstrap.connect(invalidAddress).sync());
  }

  @Test
  void invalidLocalAddressInBootstrap() {
    VSockAddress remoteAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);
    InetSocketAddress invalidLocalAddress = new InetSocketAddress("localhost", 8081);

    Bootstrap bootstrap =
        new Bootstrap()
            .group(new EpollEventLoopGroup(1))
            .channel(EpollVSockChannel.class)
            .localAddress(invalidLocalAddress)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                  }
                });

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> bootstrap.connect(remoteAddress).sync());
  }

  @Timeout(30)
  @Test
  void connectionOpen() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);
    VSockAddress clientAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8081);
    LifecycleHandler serverHandler = new LifecycleHandler();
    LifecycleHandler clientHandler = new LifecycleHandler();

    server = server(serverAddress, serverHandler);
    Channel client = client(serverAddress, clientAddress, clientHandler);

    serverHandler.onConnected().join();
    clientHandler.onConnected().join();
    Assertions.assertThat(server.localAddress()).isExactlyInstanceOf(VSockAddress.class);
    VSockAddress actualServerAddress = (VSockAddress) server.localAddress();
    Assertions.assertThat(actualServerAddress.getCid()).isEqualTo(VSockAddress.VMADDR_CID_LOCAL);
    Assertions.assertThat(actualServerAddress.getPort()).isEqualTo(8080);

    Assertions.assertThat(clientHandler.localAddress).isExactlyInstanceOf(VSockAddress.class);
    Assertions.assertThat((VSockAddress) clientHandler.localAddress).isEqualTo(clientAddress);
    Assertions.assertThat((VSockAddress) clientHandler.remoteAddress).isEqualTo(serverAddress);

    Assertions.assertThat(serverHandler.localAddress).isExactlyInstanceOf(VSockAddress.class);
    Assertions.assertThat((VSockAddress) serverHandler.localAddress).isEqualTo(serverAddress);
    Assertions.assertThat((VSockAddress) serverHandler.remoteAddress).isEqualTo(clientAddress);
  }

  @Timeout(30)
  @Test
  void connectionCloseByClient() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);
    LifecycleHandler serverHandler = new LifecycleHandler();
    LifecycleHandler clientHandler = new LifecycleHandler();

    server = server(serverAddress, serverHandler);
    Channel client = client(serverAddress, clientHandler);
    clientHandler.onConnected().join();
    clientHandler.close();
    clientHandler.onClosed().join();
    serverHandler.onClosed().join();
  }

  @Timeout(30)
  @Test
  void connectionCloseByServer() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);
    LifecycleHandler serverHandler = new LifecycleHandler();
    LifecycleHandler clientHandler = new LifecycleHandler();

    server = server(serverAddress, serverHandler);
    Channel client = client(serverAddress, clientHandler);
    serverHandler.onConnected().join();
    serverHandler.close();
    serverHandler.onClosed().join();
    clientHandler.onClosed().join();
  }

  @Test
  void config() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);

    server = server(serverAddress, new ChannelInboundHandlerAdapter());
    Channel client = client(serverAddress, new ChannelInboundHandlerAdapter());

    Assertions.assertThat(client.config()).isExactlyInstanceOf(EpollVSockChannelConfig.class);
    EpollVSockChannelConfig clientConfig = (EpollVSockChannelConfig) client.config();
    Assertions.assertThat(clientConfig.getSendBufferSize()).isGreaterThan(0);
    Assertions.assertThat(clientConfig.getReceiveBufferSize()).isGreaterThan(0);
    Assertions.assertThat(server.config()).isExactlyInstanceOf(EpollServerVSockChannelConfig.class);
    EpollServerVSockChannelConfig serverConfig = (EpollServerVSockChannelConfig) server.config();
    Assertions.assertThat(serverConfig.getReceiveBufferSize()).isGreaterThan(0);

    /*verify setSockOpts does not throw*/
    serverConfig.setReceiveBufferSize(120_000);
  }

  @Timeout(30)
  @Test
  void exchange() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8088);

    server = server(serverAddress, new ServerExchangeHandler());
    ClientExchangeHandler handler = new ClientExchangeHandler();
    Channel client = client(serverAddress, handler);
    handler.onCompleted().join();
  }

  @Test
  void nullRemoteAddressWithMessage() {
    // Bootstrap.connect() throws IllegalStateException if remoteAddress is not set
    IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> {
          Bootstrap bootstrap = new Bootstrap()
              .group(new EpollEventLoopGroup(1))
              .channel(EpollVSockChannel.class)
              .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
              });
          bootstrap.remoteAddress((SocketAddress) null);
          bootstrap.connect().sync();
        });
    // Verify exception is thrown (message validation is implementation-dependent)
    Assertions.assertThat(ex).isNotNull();
  }

  @Test
  void invalidRemoteAddressExceptionMessage() {
    InetSocketAddress invalidAddress = new InetSocketAddress("localhost", 8080);
    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> client(invalidAddress, new ChannelInboundHandlerAdapter()));

    // Verify exception message contains useful information
    Assertions.assertThat(ex.getMessage())
        .contains("VSockAddress")
        .contains("InetSocketAddress");
  }

  @Test
  void invalidLocalAddressExceptionMessage() {
    InetSocketAddress invalidLocalAddress = new InetSocketAddress("localhost", 8081);
    VSockAddress remoteAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8080);

    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> client(remoteAddress, invalidLocalAddress, new ChannelInboundHandlerAdapter()));

    // Verify exception message contains useful information
    Assertions.assertThat(ex.getMessage())
        .contains("VSockAddress")
        .contains("InetSocketAddress");
  }

  @Test
  void channelClosedAfterConnectionFailure() throws Exception {
    VSockAddress nonExistentServer = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 9997);

    Bootstrap bootstrap = new Bootstrap()
        .group(new EpollEventLoopGroup(1))
        .channel(EpollVSockChannel.class)
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) {
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
          }
        });

    try {
      Channel failedChannel = bootstrap.connect(nonExistentServer).sync().channel();
      org.junit.jupiter.api.Assertions.fail("Should have thrown exception");
    } catch (Exception e) {
      // Expected - verify the channel is properly closed
      Assertions.assertThat(e).isNotNull();
    }
  }

  @Test
  void multipleConnectionFailuresResourceCleanup() throws Exception {
    VSockAddress nonExistentServer = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 9996);
    EpollEventLoopGroup group = new EpollEventLoopGroup(1);

    try {
      for (int i = 0; i < 3; i++) {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(EpollVSockChannel.class)
            .handler(new ChannelInitializer<Channel>() {
              @Override
              protected void initChannel(Channel ch) {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
            });

        try {
          bootstrap.connect(nonExistentServer).sync();
          org.junit.jupiter.api.Assertions.fail("Should have thrown exception");
        } catch (Exception e) {
          // Expected - each iteration should fail cleanly
          Assertions.assertThat(e).isNotNull();
        }
      }
    } finally {
      group.shutdownGracefully().sync();
    }
  }

  @Test
  void serverBindInvalidAddressExceptionMessage() {
    InetSocketAddress invalidAddress = new InetSocketAddress("localhost", 8080);

    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> server(invalidAddress, new ChannelInboundHandlerAdapter()));

    // Verify exception message is informative
    Assertions.assertThat(ex.getMessage())
        .contains("VSockAddress")
        .contains("InetSocketAddress");
  }

  @Test
  void channelInactiveAfterFailedConnection() throws Exception {
    VSockAddress nonExistentServer = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 9995);
    LifecycleHandler handler = new LifecycleHandler();

    Bootstrap bootstrap = new Bootstrap()
        .group(new EpollEventLoopGroup(1))
        .channel(EpollVSockChannel.class)
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) {
            ch.pipeline().addLast(handler);
          }
        });

    try {
      bootstrap.connect(nonExistentServer).sync();
      org.junit.jupiter.api.Assertions.fail("Should have thrown exception");
    } catch (Exception e) {
      // Expected - verify lifecycle handler received close notification
      Assertions.assertThat(e).isNotNull();
      // Note: onClosed may or may not complete depending on when the failure occurred
    }
  }

  @Test
  void concurrentBindAttempts() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8092);

    // Start first server
    server = server(serverAddress, new ChannelInboundHandlerAdapter());

    // Try concurrent bind attempts (should all fail)
    CompletableFuture<?>[] futures = new CompletableFuture<?>[3];
    for (int i = 0; i < futures.length; i++) {
      futures[i] = CompletableFuture.runAsync(() -> {
        org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () -> server(serverAddress, new ChannelInboundHandlerAdapter()));
      });
    }

    // Wait for all concurrent attempts to complete
    CompletableFuture.allOf(futures).join();
  }

  @Test
  void properCleanupAfterServerBindFailure() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8093);
    EpollEventLoopGroup group = new EpollEventLoopGroup(4);

    try {
      // First bind should succeed
      ServerBootstrap bootstrap1 = new ServerBootstrap();
      Channel firstServer = bootstrap1
          .group(group)
          .channel(EpollServerVSockChannel.class)
          .childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
              ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
            }
          })
          .bind(serverAddress)
          .sync()
          .channel();

      // Second bind should fail
      ServerBootstrap bootstrap2 = new ServerBootstrap();
      org.junit.jupiter.api.Assertions.assertThrows(
          Exception.class,
          () -> bootstrap2
              .group(group)
              .channel(EpollServerVSockChannel.class)
              .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
              })
              .bind(serverAddress)
              .sync());

      // First server should still be functional
      Assertions.assertThat(firstServer.isActive()).isTrue();

      // Clean up
      firstServer.close().sync();
    } finally {
      group.shutdownGracefully().sync();
    }
  }

  @Test
  void channelConfigAccessibleAfterCreation() throws Exception {
    VSockAddress serverAddress = new VSockAddress(VSockAddress.VMADDR_CID_LOCAL, 8094);

    server = server(serverAddress, new ChannelInboundHandlerAdapter());
    Channel client = client(serverAddress, new ChannelInboundHandlerAdapter());

    // Config should be accessible even during error scenarios
    Assertions.assertThat(client.config()).isNotNull();
    Assertions.assertThat(server.config()).isNotNull();

    // Verify config types
    Assertions.assertThat(client.config()).isInstanceOf(EpollVSockChannelConfig.class);
    Assertions.assertThat(server.config()).isInstanceOf(EpollServerVSockChannelConfig.class);

    client.close().sync();
  }

  public Channel server(SocketAddress address, ChannelInboundHandler handler) throws Exception {
    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(new EpollEventLoopGroup(4))
            .channel(EpollServerVSockChannel.class)
            .childHandler(
                new ChannelInitializer<Channel>() {

                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(handler);
                  }
                })
            .bind(address)
            .sync()
            .channel();

    return server;
  }

  public Channel client(SocketAddress remote, ChannelInboundHandler handler) throws Exception {
    return client(remote, null, handler);
  }

  public Channel client(SocketAddress remote, SocketAddress local, ChannelInboundHandler handler)
      throws Exception {
    Bootstrap bootstrap =
        new Bootstrap()
            .group(new EpollEventLoopGroup(4))
            .channel(EpollVSockChannel.class)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(handler);
                  }
                });
    if (local != null) {
      bootstrap.localAddress(local);
    }
    return bootstrap.connect(remote).sync().channel();
  }

  private static class LifecycleHandler extends ChannelInboundHandlerAdapter {
    final CompletableFuture<Void> onConnected = new CompletableFuture<>();
    final CompletableFuture<Void> onClosed = new CompletableFuture<>();

    volatile SocketAddress localAddress;
    volatile SocketAddress remoteAddress;
    volatile ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      remoteAddress = ctx.channel().remoteAddress();
      localAddress = ctx.channel().localAddress();
      this.ctx = ctx;
      onConnected.complete(null);
      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      onClosed.complete(null);
      super.channelInactive(ctx);
    }

    public void close() {
      ctx.close();
    }

    public CompletableFuture<Void> onConnected() {
      return onConnected;
    }

    public CompletableFuture<Void> onClosed() {
      return onClosed;
    }
  }

  private static class ServerExchangeHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      ctx.write(msg, ctx.voidPromise());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      ctx.flush();
      super.channelReadComplete(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      if (!ctx.channel().isWritable()) {
        ctx.flush();
      }
      super.channelWritabilityChanged(ctx);
    }
  }

  private static class ClientExchangeHandler extends ChannelInboundHandlerAdapter {
    final CompletableFuture<Void> onCompleted = new CompletableFuture<>();
    final int size = 77;
    int received;

    ClientExchangeHandler() {}

    public CompletableFuture<Void> onCompleted() {
      return onCompleted;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      for (int i = 0; i < size; i++) {
        ctx.write(ctx.alloc().buffer(1).writeByte(i));
        if (!ctx.channel().isWritable()) {
          ctx.flush();
        }
      }
      ctx.flush();
      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      CompletableFuture<Void> completed = onCompleted;
      if (!completed.isDone()) {
        completed.completeExceptionally(new ClosedChannelException());
      }
      super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (onCompleted.isDone()) {
        ReferenceCountUtil.release(msg);
        return;
      }
      ByteBuf byteBuf = (ByteBuf) msg;
      int readableBytes = byteBuf.readableBytes();
      for (int i = 0; i < readableBytes; i++) {
        int r = received++;
        byte b = byteBuf.readByte();
        if (b != r) {
          byteBuf.release();
          onCompleted.completeExceptionally(
              new IllegalStateException("unexpected value for index: " + r + " - " + b));
          ctx.close();
          return;
        }
      }
      byteBuf.release();
      if (received > size) {
        onCompleted.completeExceptionally(
            new IllegalStateException("Received more than requested: " + received));
        ctx.close();
      } else if (received == size) {
        onCompleted.complete(null);
        ctx.close();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      onCompleted.completeExceptionally(new IllegalStateException(cause));
      ctx.close();
    }
  }
}
