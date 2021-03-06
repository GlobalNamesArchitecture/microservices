package org.globalnames
package index
package namefilter

import javax.inject.{Inject, Singleton}
import com.twitter.bijection.Conversion.asMethod
import com.twitter.bijection.twitter_util.UtilBijections._
import com.twitter.inject.Logging
import com.twitter.util.{Future => TwitterFuture}

import scala.concurrent.{Future => ScalaFuture}
import org.apache.commons.lang3.StringUtils.capitalize
import index.dao.{DBResultObj, Tables => T}
import index.dao.Projections._
import thrift._
import thrift.namefilter._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure, Try}
import scalaz.syntax.std.boolean._

final case class NameFilterQueryBuilder(qry: NameFilter.NameStringsQuery) {
  private val unaccent = SimpleFunction.unary[String, String]("unaccent")
  private val fUnaccent = SimpleFunction.unary[String, String]("f_unaccent")
  private val unaccentOpt = SimpleFunction.unary[Option[String], Option[String]]("unaccent")

  private[namefilter] def resolveCanonical(canonicalName: String) = {
    val canonicalNameUuid = UuidGenerator.generate(canonicalName)
    qry.filter { ns =>
      ns.canonicalUuid =!= UuidGenerator.EmptyUuid && ns.canonicalUuid === canonicalNameUuid
    }
  }

  private[namefilter] def resolveCanonicalLike(canonicalName: String) = {
    if (canonicalName.length <= 3) {
      qry.take(0)
    } else {
      val canonicalNameLike = canonicalName + "%"
      qry.filter { x => x.canonical.like(canonicalNameLike) }
    }
  }

  private[namefilter] def resolveAuthor(authorName: String) = {
    val query = T.NameStrings_AuthorWords.filter { aw =>
      aw.authorWord === unaccent(authorName.toUpperCase)
    }.map { _.nameUuid }
    qry.filter { ns => ns.id.in(query) }
  }

  private[namefilter] def resolveAuthorWildcard(authorName: String) = {
    if (authorName.isEmpty) {
      qry.take(0)
    } else {
      val authorNameLike = authorName + "%"
      val query = T.NameStrings_AuthorWords.filter { aw =>
        aw.authorWord.like(unaccent(authorNameLike.toUpperCase))
      }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveYear(year: String) = {
    Try(year.toInt) match {
      case Success(yr) if yr < 1753 =>
        qry.take(0)
      case _ =>
        val query = T.NameStrings_Year.filter { x => x.year === year }.map { _.nameUuid }
        qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveYearWildcard(year: String) = {
    if (year.isEmpty) {
      qry.take(0)
    } else {
      val yearLike = year + "%"
      val query = T.NameStrings_Year.filter { yw => yw.year.like(yearLike) }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveUninomial(uninomial: String) = {
    val query = T.NameStrings_Uninomial.filter { uw =>
      uw.uninomial === unaccent(uninomial.toUpperCase)
    }.map { _.nameUuid }
    qry.filter { ns => ns.id.in(query) }
  }

  private[namefilter] def resolveUninomialWildcard(uninomial: String) = {
    if (uninomial.length <= 3) {
      qry.take(0)
    } else {
      val uninomialLike = uninomial + "%"
      val query = T.NameStrings_Uninomial.filter { uw =>
        uw.uninomial.like(unaccent(uninomialLike.toUpperCase))
      }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveGenus(genus: String) = {
    val query = T.NameStrings_Genus.filter { uw => uw.genus === unaccent(genus.toUpperCase) }
                 .map { _.nameUuid }
    qry.filter { ns => ns.id.in(query) }
  }

  private[namefilter] def resolveGenusWildcard(genus: String) = {
    if (genus.length <= 3) {
      qry.take(0)
    } else {
      val genusLike = genus + "%"
      val query = T.NameStrings_Genus.filter { uw =>
        uw.genus.like(unaccent(genusLike.toUpperCase))
      }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveSpecies(species: String) = {
    val query = T.NameStrings_Species.filter { sw =>
      sw.species === unaccent(species.toUpperCase)
    }.map { _.nameUuid }
    qry.filter { ns => ns.id.in(query) }
  }

  private[namefilter] def resolveSpeciesWildcard(species: String) = {
    if (species.length <= 3) {
      qry.take(0)
    } else {
      val speciesLike = species + "%"
      val query = T.NameStrings_Species.filter { sw =>
        sw.species.like(unaccent(speciesLike.toUpperCase))
      }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveSubspecies(subspecies: String) = {
    val query = T.NameStrings_Subspecies.filter { ssw =>
      ssw.subspecies === unaccent(subspecies.toUpperCase)
    }.map { _.nameUuid }
    qry.filter { ns => ns.id.in(query) }
  }

  private[namefilter] def resolveSubspeciesWildcard(subspecies: String) = {
    if (subspecies.length <= 3) {
      qry.take(0)
    } else {
      val subspeciesLike = subspecies + "%"
      val query = T.NameStrings_Subspecies.filter { ssw =>
        ssw.subspecies.like(unaccent(subspeciesLike.toUpperCase))
      }.map { _.nameUuid }
      qry.filter { ns => ns.id.in(query) }
    }
  }

  private[namefilter] def resolveNameStrings(nameStringQuery: String) = {
    qry.filter { ns => unaccent(ns.name) === unaccent(nameStringQuery) }
  }

  private[namefilter] def resolveNameStringsLike(nameStringQuery: String) = {
    if (nameStringQuery.length < 3) {
      qry.take(0)
    } else {
      val nameStringQueryLike = nameStringQuery + "%"
      qry.filter { ns => fUnaccent(ns.name).like(fUnaccent(nameStringQueryLike)) }
    }
  }

  private[namefilter] def resolveExact(exact: String) = {
    val exactUuid = UuidGenerator.generate(exact)
    qry.filter { ns => ns.id === exactUuid }
  }

  private[namefilter] def resolveWord(word: String) = {
    qry.filter { ns => ns.name.like(s"%$word%") }
  }
}

@Singleton
class NameFilter @Inject()(database: Database) extends Logging {
  import NameFilter._
  import QueryParser._

  private def valueCleaned(value: String, modifier: Modifier): String = {
    val trimmed = value.replaceAll("\\s{2,}", " ").replaceAll("\\%", " ").trim
    modifier match {
      case CanonicalModifier if !trimmed.startsWith("x ") => capitalize(trimmed)
      case NameStringModifier => capitalize(trimmed)
      case _ => trimmed
    }
  }

  private
  def queryComplete(nameStringsQuery: NameStringsQuery,
                    dataSourceIds: Seq[Int]): ScalaFuture[Seq[ResultDB]] = {
    val query = for {
      ns <- nameStringsQuery
      nsi <- T.NameStringIndices.filter { nsi => nsi.nameStringId === ns.id }
      ds <- T.DataSources.filter { ds => ds.id === nsi.dataSourceId }
    } yield (ns, nsi, ds)

    val queryWithDataSources =
      if (dataSourceIds.isEmpty) {
        query
      } else {
        query.filter { case (_, _, ds) => ds.id.inSetBind(dataSourceIds) }
      }

    val queryJoined = queryWithDataSources
      .joinLeft(T.NameStringIndices).on { case ((_, nsi_l, _), nsi_r) =>
        nsi_l.acceptedTaxonId =!= "" &&
          nsi_l.dataSourceId === nsi_r.dataSourceId && nsi_l.acceptedTaxonId === nsi_r.taxonId
      }
      .joinLeft(T.NameStrings).on { case ((_, nsi), ns) => ns.id === nsi.map { _.nameStringId } }
      .map { case (((ns, nsi, ds), nsiAccepted), nsAccepted) =>
        DBResultObj.project(ns, nsi, ds, nsAccepted, nsiAccepted)
      }
      .take(1000)
    database.run(queryJoined.result)
  }

  private def cleanQuery(str: String): String = {
    val searchTerm = str.replaceAll("%", "")
                        .replaceAll("\\s+\\*+\\s+", " ")
                        .replaceAll("\u00D7", "x")
                        .replaceAll("\\s+", " ")
                        .trim
    searchTerm
  }

  private def buildNameStringsQuery(searches: SearchQuery) = {
    val nameStringsQuery = searches.parts.foldLeft(NameFilterQueryBuilder(T.NameStrings)) {
      (queryBuilder, sp) =>
        sp.modifier match {
          case ExactModifier =>
            val word = sp.words.map { _.value }.mkString(" ")
            queryBuilder.resolveExact(word)

          case NameStringModifier =>
            val wildcard = sp.words.lastOption.exists { _.wildcard }
            val word = sp.words.map { _.value }.mkString(" ")
            if (wildcard) queryBuilder.resolveNameStringsLike(word)
            else queryBuilder.resolveNameStrings(word)

          case CanonicalModifier =>
            val wildcard = sp.words.lastOption.exists { _.wildcard }
            val word = sp.words.map { _.value }.mkString(" ")
            if (wildcard) queryBuilder.resolveCanonicalLike(word)
            else queryBuilder.resolveCanonical(word)

          case UninomialModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveUninomialWildcard(word.value)
              else qb.resolveUninomial(word.value)
            }

          case GenusModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveGenusWildcard(word.value)
              else qb.resolveGenus(word.value)
            }

          case SpeciesModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveSpeciesWildcard(word.value)
              else qb.resolveSpecies(word.value)
            }

          case SubspeciesModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveSubspeciesWildcard(word.value)
              else qb.resolveSubspecies(word.value)
            }

          case AuthorModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveAuthorWildcard(word.value)
              else qb.resolveAuthor(word.value)
            }

          case YearModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              if (word.wildcard) qb.resolveYearWildcard(word.value)
              else qb.resolveYear(word.value)
            }

          case WordModifier =>
            sp.words.foldLeft(queryBuilder) { (qb, word) =>
              qb.resolveWord(word.value)
            }

          case UnknownModifier(_) =>
            queryBuilder
        }
      }
    nameStringsQuery
  }

  def resolveString(request: Request): TwitterFuture[ResponseNameStrings] = {
    val cleanedQuery = cleanQuery(request.searchTerm)
    val searches = QueryParser.parse(cleanedQuery) match {
      case Success(x) => x
      case Failure(_) => SearchQuery(Seq())
    }
    logger.info(s"query: $searches")

    if (searches.parts.isEmpty) {
      val res = ResponseNameStrings(
        page = request.page,
        perPage = request.perPage,
        pagesCount = 0,
        resultsCount = 0,
        resultNameStrings = Seq()
      )
      TwitterFuture.value(res)
    } else {
      val nameStrings = buildNameStringsQuery(searches).qry
      val matchKind = MatchKind.Unknown(thrift.Unknown())

      val resultFuture = queryComplete(nameStrings, request.dataSourceIds).map { dbResults =>
        val results = dbResults.map { dbResult =>
          val matchType = MatchType(kind = matchKind, score = 0,
            kindString = MatchKindTransform.kindName(matchKind, advancedResolution = true))
          DBResultObj.create(dbResult, matchType)
        }
        .groupBy { r => r.name.uuid }
        .values.flatMap { results =>
          val resultsVec = results.toVector
          for (response <- results.headOption) yield {
            ResultNameStrings(
              name = response.name,
              canonicalName = response.canonicalName,
              results = resultsVec.sortBy { r => r.dataSource }(util.DataSource.ordering.reverse)
            )
          }
        }
        .toVector
        .sortBy { r => r.name.value }

        val pagesCount =
          results.size / request.perPage + (results.size % request.perPage > 0).compare(false)
        ResponseNameStrings(
          page = request.page,
          perPage = request.perPage,
          pagesCount = pagesCount,
          resultsCount = results.size,
          resultNameStrings =
            results.slice(request.perPage * request.page, request.perPage * (request.page + 1))
        )
      }
      resultFuture.as[TwitterFuture[ResponseNameStrings]]
    }
  }
}

object NameFilter {
  type NameStringsQuery =
    Query[T.NameStrings, T.NameStringsRow, Seq]

  implicit def queryToBuilder(qry: NameStringsQuery): NameFilterQueryBuilder =
    NameFilterQueryBuilder(qry)

}
