package scorex.crypto.hash

object JH512 extends FRHash64 {
  override protected def hf: fr.cryptohash.Digest = new fr.cryptohash.JH512
}