package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.tigerbeetle.Client
import com.tigerbeetle.UInt128
import java.math.BigInteger
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

// Concrete self-typed subclass so GenericContainer's F-bounded SELF resolves (else it infers Nothing).
final class TbContainer(image: DockerImageName) extends GenericContainer[TbContainer](image)

// A single-replica TigerBeetle dev cluster in a container: format the data file, then start the replica.
// Mirrors the house pattern (Athena runs the same binary as a local process).
object TestTigerBeetle {

  private val script =
    "/tigerbeetle format --cluster=0 --replica=0 --replica-count=1 /tmp/0_0.tigerbeetle && " +
      "/tigerbeetle start --addresses=0.0.0.0:3000 /tmp/0_0.tigerbeetle"

  def client: Resource[IO, Client] =
    Resource
      .make(IO {
        // GenericContainer is F-bounded (SELF), so configure imperatively rather than chaining.
        val c = new TbContainer(DockerImageName.parse("ghcr.io/tigerbeetle/tigerbeetle:0.16.46"))
        c.addExposedPort(Integer.valueOf(3000))
        c.setCommand("-c", script)
        c.setWaitStrategy(Wait.forListeningPort())
        val _ = c.withCreateContainerCmdModifier { cmd =>
          val _ = cmd.withEntrypoint("/bin/sh")
          val _ = cmd.getHostConfig.withSecurityOpts(java.util.Collections.singletonList("seccomp=unconfined"))
          ()
        }
        c.start()
        c
      })(c => IO(c.stop()))
      .flatMap { c =>
        Resource.make(
          // TigerBeetle's client requires a numeric IP, not a hostname (Docker maps to 127.0.0.1).
          IO(new Client(UInt128.asBytes(BigInteger.ZERO), Array(s"127.0.0.1:${c.getMappedPort(3000)}")))
        )(client => IO(client.close()))
      }
}
