package org.globalnames
package index
package nameresolver

import java.util.UUID

import com.twitter.bijection.Conversion.asMethod
import com.twitter.bijection.twitter_util.UtilBijections._
import com.twitter.inject.Logging
import com.twitter.util.{Future => TwitterFuture}
import MatchTypeScores._
import index.dao.{DBResultObj, Tables => T}
import index.dao.Projections._
import index.{thrift => t}
import org.globalnames.index.thrift.nameresolver.ResultScored
import thrift.{MatchKind => MK, matcher => m, nameresolver => nr}
import util.{DataSource, UuidEnhanced}
import util.UuidEnhanced._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future => ScalaFuture}
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._

object NameResolver {
  type NameStringsQuery = Query[T.NameStrings, T.NameStringsRow, Seq]

  final case class RequestResponse(total: Int,
                                   request: NameInputParsed,
                                   results: Seq[nr.ResultScored],
                                   preferredResults: Seq[nr.ResultScored]) {
    val response: nr.Response = {
      val rsnss =
        results
          .groupBy { r => r.result.name.uuid }
          .values.flatMap { results =>
            val rpdss =
              for ((ds, vs) <- results.groupBy { r => r.result.dataSource }) yield {
                nr.ResultScoredPerDataSource(
                  dataSource = ds,
                  resultsScored = vs
                )
              }
            val rpdssVec = rpdss.toVector

            val datasourceBestQuality =
              if (rpdssVec.isEmpty) {
                thrift.DataSourceQuality.Unknown
              } else {
                rpdssVec.map { rpds => rpds.dataSource }.max(util.DataSource.ordering).quality
              }

            for (response <- results.headOption) yield {
              nr.ResultScoredNameString(
                name = response.result.name,
                canonicalName = response.result.canonicalName,
                datasourceBestQuality = datasourceBestQuality,
                resultsScoredPerDataSource = rpdss.toVector
                  .sortBy { rpds => rpds.dataSource }(util.DataSource.ordering.reverse)
              )
            }
          }
          .toVector
          .sortBy { r => r.name.value }

      nr.Response(total = total,
        suppliedInput = request.nameInput.value,
        suppliedId = request.nameInput.suppliedId,
        resultScoredNameStrings = rsnss,
        preferredResultsScored = preferredResults
      )
    }
  }
}

class NameResolver(request: nr.Request)
                  (implicit database: Database,
                            matcherClient: m.Service.FutureIface)
  extends Logging {

  import NameResolver._

  private def logInfo(message: String): Unit = {
    logger.info(s"(Request hash code: ${request.hashCode}) $message")
  }

  private val dataSourceIds: Seq[Int] =
    (request.preferredDataSourceIds ++ request.dataSourceIds).distinct
  private val takeCount: Int = request.perPage.min(1000).max(0)
  private val dropCount: Int = (request.page * request.perPage).max(0)
  private val namesParsed: Vector[NameInputParsed] =
    request.nameInputs
           .withFilter { ni => ni.value.nonEmpty }
           .map { ni => NameInputParsed(ni) }
           .toVector
  private val namesParsedMap: Map[UUID, NameInputParsed] =
    namesParsed.map { np => np.parsed.preprocessorResult.id -> np }.toMap

  private def exactNamesQuery(nameUuid: Rep[UUID], canonicalNameUuid: Rep[UUID]) = {
    T.NameStrings.filter { ns => ns.id === nameUuid || ns.canonicalUuid === canonicalNameUuid }
  }

  private def exactNamesQueryWildcard(query: Rep[String]) = {
    T.NameStrings.filter { ns => ns.name.like(query) || ns.canonical.like(query) }
  }

  private
  def queryWithSurrogates(nameStringsQuery: NameStringsQuery): ScalaFuture[Seq[ResultDB]] = {
    val nameStringsQuerySurrogates =
      if (request.withSurrogates) nameStringsQuery
      else nameStringsQuery.filter { ns => ns.surrogate.isEmpty || !ns.surrogate }

    val query = for {
      ns <- nameStringsQuerySurrogates
      nsi <- T.NameStringIndices.filter { nsi => nsi.nameStringId === ns.id }
      ds <- {
        val ds = T.DataSources.filter { ds => ds.id === nsi.dataSourceId }
        dataSourceIds.isEmpty ? ds | ds.filter { ds => ds.id.inSetBind(dataSourceIds) }
      }
    } yield (ns, nsi, ds)

    val queryJoin = query
      .joinLeft(T.NameStringIndices).on { case ((_, nsi_l, _), nsi_r) =>
        nsi_l.acceptedTaxonId =!= "" &&
          nsi_l.dataSourceId === nsi_r.dataSourceId && nsi_l.acceptedTaxonId === nsi_r.taxonId
      }
      .joinLeft(T.NameStrings).on { case ((_, nsi), ns) =>
        ns.id === nsi.map { _.nameStringId }
      }
      .map { case (((ns, nsi, ds), nsiAccepted), nsAccepted) =>
        DBResultObj.project(ns, nsi, ds, nsAccepted, nsiAccepted)
      }

    database.run(queryJoin.result)
  }

  private
  def queryExactMatchesByUuid(): ScalaFuture[Seq[RequestResponse]] = {
    val canonicalUuids = namesParsed.flatMap { _.parsed.canonizedUuid().map { _.id } }.distinct
    val nameUuids = namesParsed.map { _.parsed.preprocessorResult.id }.distinct
    logInfo(s"[Exact match] Name UUIDs: ${nameUuids.size}. Canonical UUIDs: ${canonicalUuids.size}")
    val qry = T.NameStrings.filter { ns =>
      ns.id.inSetBind(nameUuids) || ns.canonicalUuid.inSetBind(canonicalUuids)
    }
    val reqResp = queryWithSurrogates(qry).map { databaseResults =>
      logInfo(s"[Exact match] Database fetched")
      val nameUuidMatches = databaseResults.groupBy { r => r.ns.id }.withDefaultValue(Seq())
      val canonicalUuidMatches =
        databaseResults.groupBy { r => r.ns.canonicalUuid }
                       .filterKeys { key => key.isDefined && key != UuidGenerator.EmptyUuid.some }
                       .withDefaultValue(Seq())

      namesParsed.map { nameParsed =>
        val databaseMatches = {
          val nums = nameUuidMatches(nameParsed.parsed.preprocessorResult.id)
          val cums = canonicalUuidMatches(nameParsed.parsed.canonizedUuid().map { _.id })
          (nums ++ cums).distinct
        }

        def composeResult(r: ResultDB): ResultScored = {
          val matchKind: MK =
            if (r.ns.id == nameParsed.parsed.preprocessorResult.id) {
              MK.ExactMatch(thrift.ExactMatch())
            } else if (
              (for (nsCan <- r.ns.canonicalUuid; npCan <- nameParsed.parsed.canonizedUuid())
                yield nsCan == npCan.id).getOrElse(false)) {
              MK.CanonicalMatch(thrift.CanonicalMatch())
            } else {
              MK.Unknown(thrift.Unknown())
            }
          val dbResult =
            DBResultObj.create(r, createMatchType(matchKind, request.advancedResolution))
          val score = ResultScores(nameParsed, dbResult).compute
          nr.ResultScored(dbResult, score)
        }

        val responseResults =
          for {
            result <- databaseMatches
            if request.dataSourceIds.isEmpty || request.dataSourceIds.contains(result.ds.id)
          } yield composeResult(result)
        val preferredResponseResults =
          for (result <- databaseMatches if request.preferredDataSourceIds.contains(result.ds.id))
          yield composeResult(result)

        RequestResponse(total = responseResults.size,
                        request = nameParsed,
                        results = responseResults,
                        preferredResults = preferredResponseResults)
      }
    }
    reqResp
  }

  private
  def fuzzyMatch(scientificNames: Seq[String],
                 advancedResolution: Boolean): ScalaFuture[Seq[RequestResponse]] = {
    logInfo(s"[Fuzzy match] Names: ${scientificNames.size}")
    if (scientificNames.isEmpty) {
      ScalaFuture.successful(Seq())
    } else {
      matcherClient.findMatches(scientificNames, dataSourceIds, advancedResolution)
                           .as[ScalaFuture[Seq[thrift.matcher.Response]]].flatMap { fuzzyMatches =>
        val uuids =
          fuzzyMatches.flatMap { fm => fm.results.map { r => r.nameMatched.uuid: UUID } }.distinct
        logInfo("[Fuzzy match] Matcher service response received. " +
                s"Database request for ${uuids.size} records")
        val qry = T.NameStrings.filter { ns => ns.canonicalUuid.inSetBind(uuids) }
        queryWithSurrogates(qry).map { nameStringsDB =>
          logInfo(s"[Fuzzy match] Database response: ${nameStringsDB.size} records")
          val nameStringsDBMap = nameStringsDB.groupBy { r => r.ns.canonicalUuid }
                                              .withDefaultValue(Seq())

          fuzzyMatches.map { fuzzyMatch =>
            val nameInputParsed = namesParsedMap(fuzzyMatch.inputUuid)
            val results =
              for {
                fuzzyResult <- fuzzyMatch.results
                canId = UuidEnhanced.thriftUuid2javaUuid(fuzzyResult.nameMatched.uuid)
                result <- nameStringsDBMap(canId.some)
                if request.dataSourceIds.isEmpty || request.dataSourceIds.contains(result.ds.id)
                dbResult = DBResultObj.create(
                  result, createMatchType(fuzzyResult.matchKind, request.advancedResolution))
                score = ResultScores(nameInputParsed, dbResult).compute
              } yield {
                nr.ResultScored(dbResult, score)
              }
            val preferredResults =
              for {
                fuzzyResult <- fuzzyMatch.results
                canId = UuidEnhanced.thriftUuid2javaUuid(fuzzyResult.nameMatched.uuid)
                result <- nameStringsDBMap(canId.some)
                if request.preferredDataSourceIds.contains(result.ds.id)
                dbResult = DBResultObj.create(
                  result, createMatchType(fuzzyResult.matchKind, request.advancedResolution))
                score = ResultScores(nameInputParsed, dbResult).compute
              } yield {
                nr.ResultScored(dbResult, score)
              }
            RequestResponse(total = fuzzyMatch.results.size,
                            request = nameInputParsed,
                            results = results,
                            preferredResults = preferredResults)
          }
        }
      }
    }
  }

  private
  def computeContext(responses: Seq[RequestResponse]): nr.Responses = {
    val contexts =
      responses.flatMap { response => response.results }
               .groupBy { _.result.dataSource }
               .mapValues { results =>
                 ContextFinder.find(results.flatMap { _.result.classification.path })
               }
               .toSeq.map { case (ds, path) => t.Context(ds, path) }
    nr.Responses(responses = responses.map { _.response },
                 contexts = contexts)
  }

  private val scoreOrdering: Ordering[t.Score] = new Ordering[t.Score] {
    private val epsilon = 1e-6
    private val doubleOrdering: Ordering[Double] = new Ordering[Double] {
      override def compare(x: Double, y: Double): Int = {
        if (Math.abs(x - y) < epsilon) 0
        else Ordering.Double.compare(x, y)
      }
    }

    override def compare(s1: t.Score, s2: t.Score): Int = {
      Ordering.Option[Double](doubleOrdering).compare(s1.value, s2.value)
    }
  }

  private val resultScoredOrdering: Ordering[nr.ResultScored] =
    new Ordering[nr.ResultScored] {
      override def compare(x: nr.ResultScored, y: nr.ResultScored): Int = {
        val dataSourceCompare =
          DataSource.ordering.compare(x.result.dataSource, y.result.dataSource)

        if (dataSourceCompare == 0) {
          val scoreOrderingCompare = scoreOrdering.compare(x.score, y.score)
          if (scoreOrderingCompare == 0) {
            Ordering.Int.reverse.compare(x.result.dataSource.recordCount,
                                         y.result.dataSource.recordCount)
          } else {
            Ordering.Option[Double].compare(x.score.value, y.score.value)
          }
        } else {
          dataSourceCompare
        }
      }
    }

  private
  def rearrangeResults(responses: Seq[RequestResponse]): Seq[RequestResponse] =
    responses.map { response =>
      val results =
        if (request.bestMatchOnly) {
          response.results.nonEmpty ? Seq(response.results.max(resultScoredOrdering)) | Seq()
        } else {
          response.results.sorted(resultScoredOrdering.reverse)
        }
      val preferredResultsSorted = response.preferredResults.sorted(resultScoredOrdering.reverse)
      val preferredResults =
        if (request.bestMatchOnly) {
          for {
            pdsId <- request.preferredDataSourceIds
            rs <- preferredResultsSorted.find { _.result.dataSource.id == pdsId }
          } yield rs
        } else {
          preferredResultsSorted
        }
      response.copy(results = results.slice(request.perPage * request.page,
                                            request.perPage * (request.page + 1)),
                    preferredResults = preferredResults)
    }

  def resolveExact(): TwitterFuture[nr.Responses] = {
    logInfo(s"[Resolution] Resolution started for ${request.nameInputs.size} names")
    val resultFuture = queryExactMatchesByUuid().flatMap { names =>
      val (exactMatchesByUuid, exactUnmatchesByUuid) =
        names.partition { resp => resp.results.nonEmpty }
      val (unmatched, unmatchedNotParsed) = exactUnmatchesByUuid.partition { reqResp =>
        reqResp.request.parsed.canonizedUuid().isDefined
      }
      logInfo(s"[Resolution] Exact match done. Matches count: ${exactMatchesByUuid.size}. " +
              s"Unparsed count: ${unmatchedNotParsed.size}")

      val namesForFuzzyMatch = unmatched.map { reqResp => reqResp.request.valueCapitalised }
      val fuzzyMatchesFut = fuzzyMatch(namesForFuzzyMatch, request.advancedResolution)

      fuzzyMatchesFut.map { fuzzyMatches =>
        logInfo(s"[Resolution] Fuzzy matches count: ${fuzzyMatches.size}")
        val reqResps = exactMatchesByUuid ++ fuzzyMatches ++ unmatchedNotParsed
        (rearrangeResults _ andThen computeContext)(reqResps)
      }
    }
    resultFuture.as[TwitterFuture[nr.Responses]]
  }
}
