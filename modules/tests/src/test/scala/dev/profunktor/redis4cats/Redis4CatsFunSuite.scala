/*
 * Copyright 2018-2020 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.redis4cats

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp._
import munit.FunSuite
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

abstract class Redis4CatsFunSuite(isCluster: Boolean) extends FunSuite {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]     = IO.timer(ExecutionContext.global)
  implicit val clock: Clock[IO]     = timer.clock

  val flushAllFixture = new Fixture[Unit]("FLUSHALL") {
    def apply(): Unit = ()

    override def afterEach(context: AfterEach): Unit =
      Await.result(flushAll(), Duration.Inf)
  }

  override def munitFixtures = List(flushAllFixture)

  override def munitFlakyOK: Boolean = true

  private val stringCodec = RedisCodec.Utf8

  def withAbstractRedis[A, K, V](f: RedisCommands[IO, K, V] => IO[A])(codec: RedisCodec[K, V]): Future[Unit] =
    Redis[IO].simple("redis://localhost", codec).use(f).as(assert(true)).unsafeToFuture()

  def withRedis[A](f: RedisCommands[IO, String, String] => IO[A]): Future[Unit] =
    withAbstractRedis[A, String, String](f)(stringCodec)

  private def flushAll(): Future[Unit] =
    if (isCluster) withRedisCluster(_.flushAll)
    else withRedis(_.flushAll)

  // --- Cluster ---

  lazy val redisUri = List(
    "redis://localhost:30001",
    "redis://localhost:30002",
    "redis://localhost:30003"
  ).traverse(RedisURI.make[IO](_))

  private def mkRedisCluster[K, V](codec: RedisCodec[K, V]): Resource[IO, RedisCommands[IO, K, V]] =
    for {
      uris <- Resource.liftF(redisUri)
      client <- RedisClusterClient[IO](uris: _*)
      cluster <- Redis[IO].fromClusterClient(client, codec)
    } yield cluster

  def withAbstractRedisCluster[A, K, V](
      f: RedisCommands[IO, K, V] => IO[A]
  )(codec: RedisCodec[K, V]): Future[Unit] =
    mkRedisCluster(codec).use(f).as(assert(true)).unsafeToFuture()

  def withRedisCluster[A](f: RedisCommands[IO, String, String] => IO[A]): Future[Unit] =
    withAbstractRedisCluster[A, String, String](f)(stringCodec)

}
