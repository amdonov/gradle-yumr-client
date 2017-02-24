package com.github.amdonov.gradle.plugins.yumr

import api.YumrGrpc
import api.YumrOuterClass
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Created by aarondonovan on 2/21/17.
 */
class YumrPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create("yumr", YumrPluginExtension)
        project.task('publishToYumr') {
            doLast {
                SslContext context = GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
                String[] conn = "${project.yumr.connection}".split(":")
                ManagedChannel channel = NettyChannelBuilder.forAddress(conn[0], Integer.parseInt(conn[1])).
                        sslContext(context).build()
                api.YumrOuterClass.EmptyResponse.getDefaultInstance()
                YumrGrpc.YumrStub stub = YumrGrpc.newStub(channel)
                File file = new File("${project.yumr.rpm}")
                YumrOuterClass.OpenMessage openMessage = YumrOuterClass.OpenMessage.newBuilder().setPackage(file.getName()).
                        setRepo("${project.yumr.repo}").setSize(file.length()).build()
                final CountDownLatch finishLatch = new CountDownLatch(1);
                StreamObserver<YumrOuterClass.FileMessage> rpms = stub.addPackage(new StreamObserver<YumrOuterClass.EmptyResponse>() {
                    @Override
                    void onNext(YumrOuterClass.EmptyResponse value) {

                    }

                    @Override
                    void onError(Throwable t) {
                        t.printStackTrace(System.err)
                        finishLatch.countDown()
                    }

                    @Override
                    void onCompleted() {
                        finishLatch.countDown()
                    }
                })
                // Open the package
                rpms.onNext(YumrOuterClass.FileMessage.newBuilder().setOpenMsg(openMessage).build())
                // Send the file in 2k chunks
                byte[] buffer = new byte[2048]
                file.withInputStream { is ->
                    for (int len = is.read(buffer); len != -1; len = is.read(buffer)) {
                        ByteString chunk = ByteString.copyFrom(buffer, 0, len)
                        YumrOuterClass.DataMessage dataMessage = YumrOuterClass.DataMessage.newBuilder().setContent(chunk).build()
                        rpms.onNext(YumrOuterClass.FileMessage.newBuilder().setDataMsg(dataMessage).build())
                    }
                }
                // Close the package
                rpms.onNext(YumrOuterClass.FileMessage.newBuilder().setCloseMsg(YumrOuterClass.CloseMessage.getDefaultInstance()).build())
                rpms.onCompleted()
                if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                    warning("yumr publish can not finish within 1 minutes");
                }
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}