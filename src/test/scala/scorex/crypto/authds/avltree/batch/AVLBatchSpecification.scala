package scorex.crypto.authds.avltree.batch

import com.google.common.primitives.Longs
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.PropSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scorex.crypto.authds.TwoPartyTests
import scorex.crypto.authds.avltree.{AVLKey, AVLValue}
import scorex.crypto.authds.legacy.avltree.AVLTree
import scorex.utils.Random

import scala.util.Random.{nextInt => randomInt}
import scala.util.Try

class AVLBatchSpecification extends PropSpec with GeneratorDrivenPropertyChecks with TwoPartyTests {

  val KL = 26
  val VL = 8
  val HL = 32

  property("Long updates") {
    val prover = new BatchAVLProver(KL, VL)
    var digest = prover.digest

    forAll(kvGen) { case (aKey, aValue) =>
      val oldValue: Long = prover.unauthenticatedLookup(aKey).map(Longs.fromByteArray).getOrElse(0L)
      val delta = Math.abs(Longs.fromByteArray(aValue))
      whenever(Try(Math.addExact(oldValue, delta)).isSuccess) {

        val currentMods = Modification.convert(Seq(UpdateLongBy(aKey, delta)))

        currentMods foreach (m => prover.performOneModification(m._1, m._2))
        val pf = prover.generateProof

        val verifier = new BatchAVLVerifier(digest, pf, KL, VL)
        currentMods foreach (m => verifier.performOneModification(m._1, m._2))
        digest = verifier.digest.get
        prover.digest shouldEqual digest
        prover.unauthenticatedLookup(aKey) match {
          case Some(v) => require(delta + oldValue == Longs.fromByteArray(v))
          case None => require(delta + oldValue == 0)
        }
      }
    }
    prover.checkTree(true)
  }


  property("zero-mods verification on empty tree") {
    val p = new BatchAVLProver()
    p.checkTree()
    val digest = p.digest
    val pf = p.generateProof
    p.checkTree(true)
    val v = new BatchAVLVerifier(digest, pf, 32, 8, Some(0), Some(0))
    v.digest match {
      case Some(d) =>
        require(d sameElements digest, "wrong digest for zero-mods")
      case None =>
        throw new Error("zero-mods verification failed to construct tree")
    }
  }
  
  property("conversion to byte and back") {
    // There is no way to test this without building a tree with at least 2^88 leaves,
    // so we resort to a very basic test
    val p = new BatchAVLProver()
    val digest = p.digest
    var i:Int = 0
    for (i<-0 to 255) {
      digest(digest.length-1) = i.toByte
      var rootNodeHeight:Int = digest.last.toInt
      if (rootNodeHeight < 0) {
        rootNodeHeight+=256;
      }
      rootNodeHeight shouldBe i
    }
  }
  

  property("various verifier fails") {
    val p = new BatchAVLProver()

    p.checkTree()
    for (i <- 0 until 1000) {
      require(p.performOneModification(Insert(Random.randomBytes(), Random.randomBytes(8))).isSuccess, "failed to insert")
      p.checkTree()
    }
    p.generateProof

    var digest = p.digest
    for (i <- 0 until 50)
      require(p.performOneModification(Insert(Random.randomBytes(), Random.randomBytes(8))).isSuccess, "failed to insert")

    var pf = p.generateProof
    // see if the proof for 50 mods will be allowed when we permit only 2
    var v = new BatchAVLVerifier(digest, pf, 32, 8, Some(2), Some(0))
    require(v.digest.isEmpty, "Failed to reject too long a proof")

    // see if wrong digest will be allowed
    v = new BatchAVLVerifier(Random.randomBytes(), pf, 32, 8, Some(50), Some(0))
    require(v.digest.isEmpty, "Failed to reject wrong digest")

    for (i <- 0 until 10) {
      digest = p.digest
      for (i <- 0 until 8)
        require(p.performOneModification(Insert(Random.randomBytes(), Random.randomBytes(8))).isSuccess, "failed to insert")

      v = new BatchAVLVerifier(digest, p.generateProof, 32, 8, Some(8), Some(0))
      require(v.digest.nonEmpty, "verification failed to construct tree")
      // Try 5 inserts that do not match -- with overwhelming probability one of them will go to a leaf
      // that is not in the conveyed tree, and verifier will complain
      for (i <- 0 until 5)
        v.performOneModification(Insert(Random.randomBytes(), Random.randomBytes(8)))
      require(v.digest.isEmpty, "verification succeeded when it should have failed, because of a missing leaf")

      digest = p.digest
      val key = Random.randomBytes()
      p.performOneModification(Insert(key, Random.randomBytes(8)))
      pf = p.generateProof
      p.checkTree()

      // Change the direction of the proof and make sure verifier fails
      pf(pf.length - 1) = (~pf(pf.length - 1)).toByte
      v = new BatchAVLVerifier(digest, pf, 32, 8, Some(1), Some(0))
      require(v.digest.nonEmpty, "verification failed to construct tree")
      v.performOneModification(Insert(key, Random.randomBytes(8)))
      require(v.digest.isEmpty, "verification succeeded when it should have failed, because of the wrong direction")

      // Change the key by a large amount -- verification should fail with overwhelming probability
      // because there are 1000 keys in the tree
      // First, change the proof back to be correct
      pf(pf.length - 1) = (~pf(pf.length - 1)).toByte
      val oldKey = key(0)
      key(0) = (key(0) ^ (1 << 7)).toByte
      v = new BatchAVLVerifier(digest, pf, 32, 8, Some(1), Some(0))
      require(v.digest.nonEmpty, "verification failed to construct tree")
      v.performOneModification(Insert(key, Random.randomBytes(8)))
      require(v.digest.isEmpty, "verification succeeded when it should have failed because of the wrong key")
      // put the key back the way it should be, because otherwise it's messed up in the prover tree
      key(0) = (key(0) ^ (1 << 7)).toByte

    }
  }

  property("succesful modifications") {
    val p = new BatchAVLProver()

    val numMods = 5000

    val deletedKeys = new scala.collection.mutable.ArrayBuffer[AVLKey]

    val keysAndVals = new scala.collection.mutable.ArrayBuffer[(AVLKey, AVLValue)]

    var i = 0
    var numInserts = 0
    var numModifies = 0
    var numDeletes = 0
    var numNonDeletes = 0
    var numFailures = 0

    val t0 = System.nanoTime()
    while (i < numMods) {
      val digest = p.digest
      val n = randomInt(100)
      val j = i + n
      var numCurrentDeletes = 0
      val currentMods = new scala.collection.mutable.ArrayBuffer[Modification](n)
      while (i < j) {
        if (keysAndVals.isEmpty || randomInt(2) == 0) {
          // with prob .5 insert a new one, with prob .5 update or delete an existing one
          if (keysAndVals.nonEmpty && randomInt(10) == 0) {
            // with probability 1/10 cause a fail by inserting already existing
            val j = Random.randomBytes(3)
            val index = randomInt(keysAndVals.size)
            val key = keysAndVals(index)._1
            require(p.performOneModification(Insert(key, Random.randomBytes(8))).isFailure, "prover succeeded on inserting a value that's already in tree")
            p.checkTree()
            require(p.unauthenticatedLookup(key).get sameElements keysAndVals(index)._2, "value changed after duplicate insert") // check insert didn't do damage
            numFailures += 1
          }
          else {
            val key = Random.randomBytes()
            val newVal = Random.randomBytes(8)
            keysAndVals += ((key, newVal))
            val mod = Insert(key, newVal)
            currentMods += mod
            require(p.performOneModification(mod).isSuccess, "prover failed to insert")
            p.checkTree()
            require(p.unauthenticatedLookup(key).get sameElements newVal, "inserted key is missing") // check insert
            numInserts += 1
          }
        }
        else {
          // with probability .25 update, with .25 delete
          if (randomInt(2) == 0) {
            // update
            if (randomInt(10) == 0) {
              // with probability 1/10 cause a fail by modifying a nonexisting key
              val key = Random.randomBytes()
              require(p.performOneModification(Update(key, Random.randomBytes(8))).isFailure, "prover updated a nonexistent value")
              p.checkTree()
              require(p.unauthenticatedLookup(key).isEmpty, "a nonexistent value appeared after an update") // check update didn't do damage
              numFailures += 1
            }
            else {
              val index = randomInt(keysAndVals.size)
              val key = keysAndVals(index)._1
              val newVal = Random.randomBytes(8)
              val mod = Update(key, newVal)
              currentMods += mod
              require(p.performOneModification(mod).isSuccess, "prover failed to update value")
              keysAndVals(index) = (key, newVal)
              require(p.unauthenticatedLookup(key).get sameElements newVal, "wrong value after update") // check update
              numModifies += 1
            }
          } else {
            // delete
            if (randomInt(10) == 0) {
              // with probability 1/10 remove a nonexisting one but without failure -- shouldn't change the tree
              val key = Random.randomBytes()
              val mod = RemoveIfExists(key)
              val d = p.digest
              currentMods += mod
              require(p.performOneModification(mod).isSuccess, "prover failed when it should have done nothing")
              require(d sameElements p.digest, "Tree changed when it shouldn't have")
              p.checkTree()
              numNonDeletes += 1
            }
            else {
              // remove an existing key
              val index = randomInt(keysAndVals.size)
              val key = keysAndVals(index)._1
              val mod = Remove(key)
              val oldVal = keysAndVals(index)._2
              currentMods += mod
              val m = Modification.convert(mod)
              require(p.performOneModification(m._1, m._2).isSuccess, "failed ot delete")
              keysAndVals -= ((key, oldVal))
              deletedKeys += key
              require(p.unauthenticatedLookup(key).isEmpty, "deleted key still in tree") // check delete
              numDeletes += 1
              numCurrentDeletes += 1
            }
          }
        }
        i += 1
      }

      val pf = p.generateProof
      p.checkTree(true)

      val v = new BatchAVLVerifier(digest, pf, 32, 8, Some(n), Some(numCurrentDeletes))
      v.digest match {
        case None =>
          require(false, "Verification failed to construct the tree")
        case Some(d) =>
          require(d sameElements digest, "Built tree with wrong digest") // Tree built successfully
      }

      Modification.convert(currentMods) foreach (m => v.performOneModification(m._1, m._2))
      v.digest match {
        case None =>
          require(false, "Verification failed")
        case Some(d) =>
          require(d sameElements p.digest, "Tree has wrong digest after verification")
      }
    }

    // Check that all the inserts, deletes, and updates we did actually stayed
    deletedKeys foreach (k => require(p.unauthenticatedLookup(k).isEmpty, "Key that was deleted is still in the tree"))
    keysAndVals foreach (pair => require(p.unauthenticatedLookup(pair._1).get sameElements pair._2, "Key has wrong value"))
  }

  property("Persistence AVL batch prover") {
    val storage = new VersionedAVLStorageMock
    val prover = new PersistentBatchAVLProver(new BatchAVLProver(KL, VL), storage)
    var digest = prover.digest

    forAll(kvGen) { case (aKey, aValue) =>
      val m = Insert(aKey, aValue)
      prover.performOneModification(m)
      val pf = prover.generateProof
      val verifier = new BatchAVLVerifier(digest, pf, KL, VL)
      verifier.digest.get
      verifier.performOneModification(m)
      prover.digest should not equal digest
      prover.digest shouldEqual verifier.digest.get

      prover.rollback(digest).isSuccess shouldBe true
      prover.digest shouldEqual digest
      prover.performOneModification(m)
      prover.generateProof
      digest = prover.digest
    } 

    val prover2 = new PersistentBatchAVLProver(new BatchAVLProver(KL, VL), storage)
    prover2.digest shouldEqual prover.digest
  }

  property("Updates with and without batching should lead to the same tree") {
    val tree = new AVLTree(KL)
    var digest = tree.rootHash()
    val oldProver = new LegacyProver(tree)
    val newProver = new BatchAVLProver(KL, VL)
    require(newProver.digest startsWith oldProver.rootHash)
    require(newProver.digest.length == oldProver.rootHash.length+1)

    forAll(kvGen) { case (aKey, aValue) =>
      val currentMods = Seq(Insert(aKey, aValue))
      oldProver.applyBatchSimple(currentMods) match {
        case bss: BatchSuccessSimple =>
          new LegacyVerifier(digest).verifyBatchSimple(currentMods, bss) shouldBe true
        case bf: BatchFailure => throw bf.error
      }

      Modification.convert(currentMods) foreach (m => newProver.performOneModification(m._1, m._2))
      val pf = newProver.generateProof

      digest = oldProver.rootHash
      require(newProver.digest startsWith digest)
      require(newProver.digest.length == oldProver.rootHash.length+1)
    }
    newProver.checkTree(true)
  }

  property("Verifier should calculate the same digest") {
    val prover = new BatchAVLProver(KL, VL)
    var digest = prover.digest

    forAll(kvGen) { case (aKey, aValue) =>
      val currentMods = Modification.convert(Seq(Insert(aKey, aValue)))

      currentMods foreach (m => prover.performOneModification(m._1, m._2))
      val pf = prover.generateProof

      val verifier = new BatchAVLVerifier(digest, pf, KL, VL)
      currentMods foreach (m => verifier.performOneModification(m._1, m._2))
      digest = verifier.digest.get

      prover.digest shouldEqual digest
    }
    prover.checkTree(true)
  }


  def kvGen: Gen[(Array[Byte], Array[Byte])] = for {
    key <- Gen.listOfN(KL, Arbitrary.arbitrary[Byte]).map(_.toArray) suchThat
      (k => !(k sameElements Array.fill(KL)(-1: Byte)) && !(k sameElements Array.fill(KL)(0: Byte)) && k.length == KL)
    value <- Gen.listOfN(VL, Arbitrary.arbitrary[Byte]).map(_.toArray)    
  } yield (key, value)

}
