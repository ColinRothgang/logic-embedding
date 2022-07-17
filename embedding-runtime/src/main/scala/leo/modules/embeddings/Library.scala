package leo.modules.embeddings

object Library {
  final def version: String = "1.5"

  def embeddingTable: Map[String, Embedding] = {
    Map(
      "$$dhol" -> DHOLEmbedding,
      "$modal" -> ModalEmbedding,
      "$alethic_modal" -> ModalEmbedding,
      "$deontic_modal" -> ModalEmbedding,
      "$epistemic_modal" -> ModalEmbedding,
      "$temporal" -> TemporalLogicEmbedding,
      "$$ddl" -> DyadicDeonticLogicEmbedding,
      "$$hybrid" -> HybridLogicEmbedding,
      "$$pal" -> PublicAnnouncementLogicEmbedding,
      "$$normative" -> NormativeDSLEmbedding
    )
  }
}
