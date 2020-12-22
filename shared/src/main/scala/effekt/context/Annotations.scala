package effekt
package context

import effekt.util.messages.ErrorReporter
import org.bitbucket.inkytonik.kiama.util.Memoiser

case class Annotation[K, V](name: String) {
  type Value = V
}

/**
 * "local" annotatations that can be backtracked
 *
 * Local annotations can be backed-up and restored to allow backtracking
 * (mostly for typer and overload resolution).
 *
 * Local annotations can be "comitted" to become global ones in the DB,
 * that are assumed to not change anymore.
 */
class Annotations private (annotations: Annotations.DB) {
  import Annotations._

  private def annotationsAt(key: Any): Map[Annotation[_, _], Any] =
    annotations.getOrElse(new Key(key), Map.empty)

  private def updateAnnotations(key: Any, annos: Map[Annotation[_, _], Any]): Annotations =
    new Annotations(annotations.updated(new Key(key), annos))

  def annotate[K, V](ann: Annotation[K, V], key: K, value: V): Annotations = {
    val anns = annotationsAt(key)
    updateAnnotations(key, anns + (ann -> value))
  }

  def annotationOption[K, V](ann: Annotation[K, V], key: K): Option[V] =
    annotationsAt(key).get(ann).asInstanceOf[Option[V]]

  def annotation[K, V](ann: Annotation[K, V], key: K)(implicit C: ErrorReporter): V =
    annotationOption(ann, key).getOrElse { C.abort(s"Cannot find ${ann.name} for '${key}'") }

  def commit()(implicit global: AnnotationsDB): Unit =
    annotations.foreach {
      case (k, annos) => global.annotate(k.key, annos)
    }
}
object Annotations {
  private type DB = Map[Annotations.Key[Any], Map[Annotation[_, _], Any]]

  private class Key[T](val key: T) {
    override val hashCode = System.identityHashCode(key)
    override def equals(o: Any) = o match {
      case k: Key[_] => hashCode == k.hashCode
      case _         => false
    }
  }

}

trait AnnotationsDB { self: ErrorReporter =>

  private type Annotations = Map[Annotation[_, _], Any]
  private val annotations: Memoiser[Any, Annotations] = Memoiser.makeIdMemoiser()
  private def annotationsAt(key: Any): Annotations = annotations.getOrDefault(key, Map.empty)

  /**
   * Bulk annotating the key
   *
   * Used by Annotations.commit to commit all temporary annotations to the DB
   */
  def annotate[K, V](key: K, value: Map[Annotation[_, _], Any]): Unit = {
    val anns = annotationsAt(key)
    annotations.put(key, anns ++ value)
  }

  def annotate[K, V](ann: Annotation[K, V], key: K, value: V): Unit = {
    val anns = annotationsAt(key)
    annotations.put(key, anns + (ann -> value))
  }

  def annotationOption[K, V](ann: Annotation[K, V], key: K): Option[V] =
    annotationsAt(key).get(ann).asInstanceOf[Option[V]]

  def annotation[K, V](ann: Annotation[K, V], key: K): V =
    annotationOption(ann, key).getOrElse { abort(s"Cannot find ${ann.name} for '${key}'") }
}
