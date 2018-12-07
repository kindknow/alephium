package org.alephium.protocol.model

import org.alephium.crypto._
import org.scalacheck.Gen

// TODO: rename as GenFixture
object ModelGen {
  private val (sk, pk) = ED25519.generateKeyPair()

  val txInputGen: Gen[TxInput] = for {
    index <- Gen.choose(0, 10)
  } yield TxInput(Keccak256.zero, index) // TODO: fixme: Has to use zero here to pass test on ubuntu

  val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose(0, 100)
  } yield TxOutput(value, pk)

  val transactionGen: Gen[Transaction] = for {
    inputNum  <- Gen.choose(0, 5)
    inputs    <- Gen.listOfN(inputNum, txInputGen)
    outputNum <- Gen.choose(0, 5)
    outputs   <- Gen.listOfN(outputNum, txOutputGen)
  } yield Transaction.from(UnsignedTransaction(inputs, outputs), sk)

  val blockGen: Gen[Block] = for {
    txNum <- Gen.choose(0, 100)
    txs   <- Gen.listOfN(txNum, transactionGen)
  } yield Block.from(Seq(Keccak256.zero), txs, 0)

  def blockGenWith(deps: Seq[Keccak256]): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 100)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(deps, txs, 0)

  def chainGen(length: Int): Gen[Seq[Block]] =
    Gen.listOfN(length, blockGen).map { blocks =>
      blocks.foldLeft(Seq.empty[Block]) {
        case (acc, block) =>
          val prevHash      = if (acc.isEmpty) Keccak256.zero else acc.last.hash
          val currentHeader = block.blockHeader
          val newHeader     = currentHeader.copy(blockDeps = Seq(prevHash))
          val newBlock      = block.copy(blockHeader = newHeader)
          acc :+ newBlock
      }
    }
}