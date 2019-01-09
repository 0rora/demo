package models.repo

import java.time.{ZoneId, ZonedDateTime}

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink}
import javax.inject.Inject
import models.repo.Payment.{Failed, Pending, Submitted, Succeeded}
import scalikejdbc.{AutoSession, _}
import stellar.sdk.model.{Asset, IssuedAmount, NativeAmount}
import stellar.sdk.model.op.PaymentOperation
import stellar.sdk.{KeyPair, PublicKeyOps}

import scala.concurrent.duration._

@javax.inject.Singleton
class PaymentRepo @Inject()() {
  implicit private val session: AutoSession = AutoSession
  private val UTC = ZoneId.of("UTC")

  val writer: Sink[Payment, NotUsed] = Flow[Payment]
    .groupedWithin(100, 1.second)
    .map {
      _.map(p =>
        Seq(p.source.accountId, p.destination.accountId, p.code, p.issuer.orNull, p.units, p.received, p.scheduled,
          p.status.toString.toLowerCase)
      )
    }.to(Sink.foreach { params =>
    sql"""
      insert into payments (source, destination, code, issuer, units, received, scheduled, status)
      values (?, ?, ?, ?, ?, ?, ?, ?)
    """.batch(params: _*).apply()
  })

  def listScheduled: Seq[Payment] = {
    sql"""
       select id, source, destination, code, issuer, units, received, scheduled, status
       from payments
       where status=${Pending.name}
       order by scheduled
    """.map(from).list().apply()
  }

  def listHistoric: Seq[Payment] = {
    sql"""
       select id, source, destination, code, issuer, units, received, scheduled, status
       from payments
       where status in ('failed', 'succeeded')
       order by id desc
    """.map(from).list().apply()
  }

  def due: Seq[Payment] = {
    sql"""
       select id, source, destination, code, issuer, units, received, scheduled, status
       from payments
       where status='pending'
       and scheduled <= ${ZonedDateTime.now.toInstant}
    """.map(from).list().apply()
  }

  private def updateStatus(ids: Seq[Long], status: Payment.Status): Unit = {
    sql"""
        update payments set status=${status.name} where id in ($ids)
    """.update().apply()
  }

  def submit(ids: Seq[Long]): Unit = updateStatus(ids, Submitted)

  def confirm(ids: Seq[Long]): Unit = updateStatus(ids, Succeeded)

  def reject(ids: Seq[Long]): Unit = updateStatus(ids, Failed)

  def earliestTimeDue: Option[ZonedDateTime] = {
    sql"""select min(scheduled) as next from payments where status='pending'""".map { rs =>
      Option(rs.timestamp("next"))
        .map(_.toInstant)
        .map(ZonedDateTime.ofInstant(_, UTC))
    }.single().apply().flatten
  }

  // todo - remove me
  def durationUntilNextDue: Option[FiniteDuration] = {
    sql"""select min(scheduled) as next from payments where status='pending'""".map {rs =>
      Option(rs.timestamp("next")).map { next =>
        val when = ZonedDateTime.ofInstant(next.toInstant, UTC)
        val now = ZonedDateTime.now
        Duration.fromNanos(math.max(0L, java.time.Duration.between(now.toInstant, when.toInstant).toNanos))
      }
    }.single().apply().flatten
  }

  private def from(rs: WrappedResultSet): Payment =
    Payment(
      id = rs.longOpt("id"),
      source = KeyPair.fromAccountId(rs.string("source")),
      destination = KeyPair.fromAccountId(rs.string("destination")),
      code = rs.string("code"),
      issuer = rs.stringOpt("issuer").map(KeyPair.fromAccountId),
      units = rs.long("units"),
      received = ZonedDateTime.ofInstant(rs.timestamp("received").toInstant, UTC),
      scheduled = ZonedDateTime.ofInstant(rs.timestamp("scheduled").toInstant, UTC),
      status = Payment.status(rs.string("status"))
    )
}

case class Payment(id: Option[Long],
                   source: PublicKeyOps,
                   destination: PublicKeyOps,
                   code: String,
                   issuer: Option[PublicKeyOps],
                   units: Long,
                   received: ZonedDateTime,
                   scheduled: ZonedDateTime,
                   status: Payment.Status) {

  def asOperation = PaymentOperation(
    destination, issuer.map(i => IssuedAmount(units, Asset(code, i))).getOrElse(NativeAmount(units)), Some(source)
  )

}

object Payment {

  def status(s: String): Status = s match {
    case "pending" => Pending
    case "submitted" => Submitted
    case "failed" => Failed
    case "succeeded" => Succeeded
    case _ => throw new Exception(s"Payment status unrecognised: $s")
  }

  sealed trait Status {
    val name: String = getClass.getSimpleName.toLowerCase().replace("$", "")
  }

  case object Pending extends Status

  case object Submitted extends Status

  case object Failed extends Status

  case object Succeeded extends Status

}


