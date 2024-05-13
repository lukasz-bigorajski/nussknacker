package pl.touk.nussknacker.ui.db.timeseries

import akka.actor.{ActorSystem, Cancellable}
import better.files.File
import cats.effect.{IO, Resource}
import com.typesafe.scalalogging.LazyLogging
import io.questdb.TelemetryConfiguration
import io.questdb.cairo.security.AllowAllSecurityContext
import io.questdb.cairo.sql.{Record, RecordCursor, RecordCursorFactory, SqlExecutionCircuitBreakerConfiguration}
import io.questdb.cairo.wal.{ApplyWal2TableJob, CheckWalTransactionsJob, WalPurgeJob}
import io.questdb.cairo.{CairoEngine, CairoException, DefaultCairoConfiguration}
import io.questdb.griffin.{DefaultSqlExecutionCircuitBreakerConfiguration, SqlException, SqlExecutionContextImpl}
import io.questdb.log.LogFactory
import pl.touk.nussknacker.ui.db.timeseries.QuestDbFEStatisticsRepository.{
  ThreadAwareObjectPool,
  createDirAndConfigureLogging,
  createTable1Query,
  createTableQuery,
  dropOldPartitionsQuery,
  dropTableQuery,
  selectAllQuery,
  selectQuery,
  tableName
}

import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.util.{Failure, Success, Try, Using}

// TODO list:
// WARNING - for now is not thread safe - multiple request at the same time will kill application
// 2. Compacting?
// 4. Configurable directory of db (for now it's tmp directory) and limiting db file space on disk.
// 5. Collecting statistics should be deactivable by configuration. (If db creation fails provide NoOp DB as fallback)
// 6. API should have better types (missing domain layer for FE statistics names).
// 6. Handling errors while creating the QuestDb.
// 7. Changing table definition and recreate
// 9. multi tenant metrics
private class QuestDbFEStatisticsRepository(private val cairoEngine: CairoEngine, private val clock: Clock)(
    private implicit val ec: ExecutionContextExecutorService
) extends FEStatisticsRepository[Future]
    with LazyLogging {

  private val sqlContext = new ThreadAwareObjectPool(() =>
    new SqlExecutionContextImpl(cairoEngine, 1).`with`(AllowAllSecurityContext.INSTANCE, null)
  )

  private val recordCursorPool = new ThreadAwareObjectPool(() => cairoEngine.select(selectQuery, sqlContext.get()))

  private val walTableWriterPool = new ThreadAwareObjectPool(() => {
    logger.warn(s"status: ${cairoEngine.getTableStatus(tableName)}")
    cairoEngine.getWalWriter(cairoEngine.getTableTokenIfExists(tableName))
  })

  private lazy val applyWal2TableJob = new ApplyWal2TableJob(cairoEngine, 1, 1)
  private lazy val walPurgeJob       = new WalPurgeJob(cairoEngine)
  private val shouldCleanUpData      = new AtomicBoolean(false)

  override def write(statistics: Map[String, Long]): Future[Unit] = withExceptionHandling(() => {
    logger.info(s"Does root exists: ${(File.temp / "nu").exists}")
    val statsWalTableWriter = walTableWriterPool.get()
    statistics.foreach { entry =>
      val row = statsWalTableWriter.newRow(currentTimeMicros())
      row.putStr(0, entry._1)
      row.putLong(1, entry._2)
      row.append()
    }
    statsWalTableWriter.commit()
    logger.info(s"Committed changes, table exists: ${cairoEngine.getTableStatus(tableName)}")
  })

  override def read(): Future[Map[String, Long]] = withExceptionHandling(() => {
    select(recordCursorPool.get()) { record =>
      val name  = record.getStrA(0).toString
      val count = record.getLong(1)
      name -> count
    }.toMap
  })

  private def select[T](recordCursorFactory: RecordCursorFactory)(mapper: Record => T): List[T] =
    Using.resource(recordCursorFactory.getCursor(sqlContext.get())) { recordCursor =>
      val buffer = ArrayBuffer.empty[T]
      val record = recordCursor.getRecord
      while (recordCursor.hasNext) {
        val entry = mapper(record)
        buffer.append(entry)
      }
      buffer.toList
    }

  private def currentTimeMicros(): Long =
    Math.multiplyExact(clock.instant().toEpochMilli, 1000L)

  private def close(): Unit = {
    // todo flush
    recordCursorPool.clear()
    walTableWriterPool.clear()
    sqlContext.clear()
    applyWal2TableJob.close()
    walPurgeJob.close()
  }

  private def initialize(): Unit = {
    cairoEngine.ddl(createTableQuery, sqlContext.get())
    cairoEngine.setWalPurgeJobRunLock(walPurgeJob.getRunLock)
  }

  private def withExceptionHandling[T](dbAction: () => T): Future[T] =
    Future {
      // some actions can succeed even if table exists so we check table existence and retry if table does not exist
      if (cairoEngine.getTableStatus(tableName) != 0) {
        recoverWithRetry(new Exception("abc"), dbAction)
      }
      Try(dbAction()).recoverWith {
        case ex: CairoException if ex.isCritical || ex.isTableDropped =>
          recoverWithRetry(ex, dbAction)
        case ex: SqlException if ex.getMessage.contains("table does not exist") =>
          recoverWithRetry(ex, dbAction)
        case ex =>
          logger.warn("DB exception", ex)
          Failure(ex)
      }.get
    }

  private def recoverWithRetry[T](ex: Exception, dbAction: () => T): Try[T] = {
    logger.warn("Statistic DB exception - trying to recover", ex)
    recreateTables() match {
      case Failure(ex) =>
        logger.warn("Exception occurred while tables recreate", ex)
        Failure(ex)
      case Success(_) =>
        logger.info("Recreate succeeded - retrying db action")
        Try(dbAction()) match {
          case f @ Failure(ex) =>
            logger.warn("Exception occurred during db retry", ex)
            f
          case s @ Success(_) =>
            logger.info("Retried db action succeeded")
            s
        }
    }
  }

  private def recreateTables(): Try[Unit] =
    Try {
      logger.info("Recreating table")
      createDirAndConfigureLogging()
//      Try(cairoEngine.ddl(s"truncate all tables", sqlContext.get())).get
//      cairoEngine.getTableSequencerAPI.releaseAll()
      recordCursorPool.clear()
      walTableWriterPool.clear()
      sqlContext.clear()
      val ctx = sqlContext.get()
//      cairoEngine.load()
      Try(cairoEngine.drop(dropTableQuery, ctx)).get
      cairoEngine.ddl(createTableQuery, ctx)
      // double check, I guess there 2 sources of true about table existence, and the problem is visible in close, maybe after fixing that problem the double check will be unnecessary
      if (cairoEngine.getTableStatus(tableName) == 1) {
        Try(cairoEngine.drop(dropTableQuery, ctx)).get
        cairoEngine.ddl(createTableQuery, ctx)
      }
    }

  // TODO: should be limited by timeout?
  private def flushDataToDisk(): Unit = {
    logger.info("Flushing data to disk")
    logger.info(s"Table status: ${cairoEngine.getTableStatus(tableName)}")
    if (cairoEngine.getTableStatus(tableName) != 0) {
      // return if table status is failed
      return
    }
    new CheckWalTransactionsJob(cairoEngine).runSerially()
    logger.info(s"metada: ${cairoEngine.getSequencerMetadata(cairoEngine.getTableTokenIfExists(tableName))}")
    // This job is assigned to the WorkerPool created in Server mode (standalone db)
    // (io.questdb.ServerMain.setupWalApplyJob, io.questdb.mp.Worker.run),
    // so it's basically assigned thread to run this job.
    Try {
      Range.inclusive(1, 3).takeWhile(idx => applyWal2TableJob.run(1) && idx <= 3)
    }.recover { case ex: Exception =>
      logger.warn("Exception thrown while applying a data to the disk.", ex)
    }
    logger.info(s"dump before cleanup: ${dump()}")
    cleanUpOldData()
    logger.info(s"dump after cleanup: ${dump()}")
  }

  private def dump(): Map[String, Long] = {
    select(cairoEngine.select(selectAllQuery, sqlContext.get())) { record =>
      val name  = record.getStrA(0).toString
      val count = record.getLong(1)
      name -> count
    }.toMap
  }

  private def scheduleRetention(): Unit =
    shouldCleanUpData.set(true)

  // compact data?
  private def cleanUpOldData(): Unit = Try {
    if (!shouldCleanUpData.get()) {
      return
    }
    shouldCleanUpData.set(false)
    logger.info("Clean up old data")
    // at least two partitions must exist
    val selectAllPartitionsQuery = s"select name, maxTimestamp from table_partitions('$tableName')"
    val allPartitions = select(cairoEngine.select(selectAllPartitionsQuery, sqlContext.get())) { record =>
      record.getStrA(0).toString -> Instant.ofEpochMilli(record.getTimestamp(1) / 1000L)
    }
    val (oldPartitions, presentPartitions) = allPartitions.span(
      _._2.truncatedTo(ChronoUnit.DAYS).getEpochSecond < Instant.now().truncatedTo(ChronoUnit.DAYS).getEpochSecond
    )
    if (oldPartitions.nonEmpty && presentPartitions.nonEmpty) {
//      cairoEngine.lockTableName(tableName, true)
      cairoEngine.ddl(dropOldPartitionsQuery, sqlContext.get())
      walPurgeJob.run(2)
//      cairoEngine.unlockTableName(cairoEngine.getTableTokenIfExists(tableName))
    }
    cairoEngine.releaseInactive()
  }.recover { case ex: Exception =>
    logger.warn("Exception thrown while cleaning data.", ex)
  }

}

object QuestDbFEStatisticsRepository extends LazyLogging {
  private val tableName = "fe_statistics"
  private val createTableQuery =
    s"CREATE TABLE IF NOT EXISTS $tableName (name string, count long, ts timestamp) TIMESTAMP(ts) PARTITION BY DAY WAL"
  private val createTable1Query =
    s"CREATE TABLE $tableName (name string, count long, ts timestamp) TIMESTAMP(ts) PARTITION BY DAY WAL"

  private val selectAllQuery =
    s"""
       |   SELECT name,
       |          sum(count)
       |     FROM $tableName
       | GROUP BY name""".stripMargin

  private val selectQuery =
    s"""
       |   SELECT name,
       |          sum(count)
       |     FROM $tableName
       |    WHERE timestamp_floor('d', ts) = timestamp_floor('d', now())
       | GROUP BY name""".stripMargin

  private val dropTableQuery =
    s"DROP TABLE IF EXISTS $tableName"
  private val dropOldPartitionsQuery =
    s"ALTER TABLE $tableName DROP PARTITION WHERE ts < timestamp_floor('d', now())"

  def create(system: ActorSystem, clock: Clock): Resource[IO, FEStatisticsRepository[Future]] = for {
    executorService <- createExecutorService()
    cairoEngine     <- createCairoEngine()
    repository      <- createRepository(executorService, cairoEngine, clock)
    // TODO: move task properties to configuration
    _ <- createTask(system, Duration(2L, TimeUnit.SECONDS), () => repository.flushDataToDisk())
    _ <- createTask(system, Duration(2L, TimeUnit.SECONDS), () => repository.scheduleRetention())
  } yield repository

  // todo move this ExecutorService properties to a configuration
  // todo log if task rejected
  private def createExecutorService(): Resource[IO, ExecutionContextExecutorService] = Resource.make(
    acquire = for {
      executorService <- IO(new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](8)))
      ec = ExecutionContext.fromExecutorService(executorService)
    } yield ec
  )(release = ec => IO(ec.shutdown()))

  private def createCairoEngine(): Resource[IO, CairoEngine] = Resource.make(
    acquire = IO {
      val nuDir = createDirAndConfigureLogging()
      new CairoEngine(new CustomCairoConfiguration(nuDir.canonicalPath))
    }
  )(release = engine => IO(engine.close()))

  private def createRepository(
      ec: ExecutionContextExecutorService,
      cairoEngine: CairoEngine,
      clock: Clock
  ): Resource[IO, QuestDbFEStatisticsRepository] = Resource.make(
    acquire = for {
      repository <- IO(new QuestDbFEStatisticsRepository(cairoEngine, clock)(ec))
      _          <- IO(repository.initialize())
    } yield repository
  )(release = repository => IO(repository.close()))

  private def createDirAndConfigureLogging(): File = {
    val nuDir: File = createRootDirIfNotExists()
//    configureLogging(nuDir)
    nuDir
  }

  private def createRootDirIfNotExists(): File = {
    val nuDir = File.temp.createChild("nu", asDirectory = true)
    logger.debug("Statistics path: {}", nuDir)
    nuDir
  }

  private def configureLogging(rootDir: File): Unit = {
    rootDir
      .createChild("conf/log.conf", createParents = true)
      .writeText(
        """
          |writers=stdout
          |w.stdout.class=io.questdb.log.LogConsoleWriter
          |w.stdout.level=ERROR
          |""".stripMargin
      )(Seq(StandardOpenOption.TRUNCATE_EXISTING), StandardCharsets.UTF_8)
    LogFactory.configureRootDir(rootDir.canonicalPath)
  }

  private def createTask(system: ActorSystem, interval: FiniteDuration, runnable: Runnable): Resource[IO, Cancellable] =
    Resource.make(
      acquire = IO(
        system.scheduler
          .scheduleWithFixedDelay(interval, interval)(runnable)(system.dispatcher)
      )
    )(release = task => IO(task.cancel()))

  private class CustomCairoConfiguration(private val root: String) extends DefaultCairoConfiguration(root) {

    override def getTelemetryConfiguration: TelemetryConfiguration = new TelemetryConfiguration {
      override def getDisableCompletely: Boolean = true
      override def getEnabled: Boolean           = false
      override def getQueueCapacity: Int         = 16
      override def hideTables(): Boolean         = false
    }

    override def getCircuitBreakerConfiguration: SqlExecutionCircuitBreakerConfiguration =
      new DefaultSqlExecutionCircuitBreakerConfiguration {
        // timeout in micros
        override def getQueryTimeout: Long = TimeUnit.SECONDS.toMicros(10L)
      }

  }

  private class ThreadAwareObjectPool[T <: AutoCloseable](objectFactory: () => T) {
    private val pool = new mutable.HashMap[Thread, T]()

    def get(): T = {
      val thread = Thread.currentThread()
      pool.getOrElse(
        thread, {
          val t = objectFactory()
          pool.put(thread, t)
          t
        }
      )
    }

    def clear(): Unit = {
      val values = pool.values.toList
      pool.clear()
      values.foreach(_.close())
    }

  }

}
