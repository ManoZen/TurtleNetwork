package com.wavesplatform.state2

import java.io.{File, PrintWriter}
import java.util.concurrent.ThreadLocalRandom

import com.typesafe.config.ConfigFactory
import com.wavesplatform.database.LevelDBWriter
import com.wavesplatform.db.LevelDBFactory
import com.wavesplatform.settings.{FunctionalitySettings, WavesSettings, loadConfig}
import org.iq80.leveldb.{DB, Options}
import scorex.account.AddressScheme
import scorex.block.Block
import scorex.transaction.assets.IssueTransaction
import scorex.transaction.{Authorized, CreateAliasTransaction, Transaction}
import scorex.utils.ScorexLogging

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Extracts data from the database to use it in RealDbBenchmark.
  * Requires a separate main file because takes too long time to run.
  */
object ExtractInfo extends App with ScorexLogging {

  if (args.length < 1) {
    log.error("Specify a path to the node config. Usage: benchmark/run /full/path/to/the/config.conf")
    System.exit(1)
  }

  val config = loadConfig(ConfigFactory.parseFile(new File(args.head)))
  val fs: FunctionalitySettings = {
    val settings = WavesSettings.fromConfig(config)
    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = settings.blockchainSettings.addressSchemeCharacter.toByte
    }
    settings.blockchainSettings.functionalitySettings
  }

  val settings = Settings.fromConfig(config)
  val db: DB = {
    val dir = new File(settings.dbPath)
    if (!dir.isDirectory) throw new IllegalArgumentException(s"Can't find directory at '${settings.dbPath}'")
    LevelDBFactory.factory.open(dir, new Options)
  }

  try {
    val state = new LevelDBWriter(db, fs)

    val nonEmptyBlockHeights: Iterator[Integer] = for {
      height     <- randomInts(2, state.height)
      (block, _) <- state.blockHeaderAndSize(height)
      if block.transactionCount > 0
    } yield height

    val nonEmptyBlocks: Iterator[Block] = nonEmptyBlockHeights
      .flatMap(state.blockAt(_))

    val (aliasTxs, restTxs) = nonEmptyBlocks
      .flatMap(_.transactionData)
      .partition {
        case _: CreateAliasTransaction => true
        case _                         => false
      }

    val accounts = for {
      b <- nonEmptyBlocks
      sender <- b.transactionData
        .collect {
          case tx: Transaction with Authorized => tx.sender
        }
        .take(100)
    } yield sender.toAddress.stringRepr
    write("accounts", settings.accountsFile, takeUniq(5000, accounts))

    val aliasTxIds = aliasTxs.map(_.asInstanceOf[CreateAliasTransaction].alias.stringRepr)
    write("aliases", settings.aliasesFile, aliasTxIds.take(1000))

    val restTxIds = restTxs.map(_.id().base58)
    write("rest transactions", settings.restTxsFile, restTxIds.take(10000))

    val assets = nonEmptyBlocks
      .flatMap { b =>
        b.transactionData.collect {
          case tx: IssueTransaction => tx.assetId()
        }
      }
      .map(_.base58)
    write("assets", settings.assetsFile, takeUniq(300, assets))
  } catch {
    case NonFatal(e) => log.error(e.getMessage, e)
  } finally {
    db.close()
    log.info("Done")
  }

  def takeUniq[T](size: Int, xs: Iterator[T]): mutable.Set[T] = {
    val r = mutable.Set.empty[T]
    xs.find { x =>
      r.add(x)
      r.size == size
    }
    r
  }

  def write(label: String, absolutePath: String, data: TraversableOnce[String]): Unit = {
    log.info(s"Writing $label to '$absolutePath'")
    val printWriter = new PrintWriter(absolutePath)
    data.foreach(printWriter.println)
    printWriter.close()
  }

  def randomInts(from: Int, to: Int): Iterator[Integer] =
    ThreadLocalRandom
      .current()
      .ints(from, to)
      .iterator()
      .asScala

}
