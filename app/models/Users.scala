package models

import com.github.tototoshi.slick.H2JodaSupport._
import org.joda.time.DateTime
import securesocial.core._
import scala.language.implicitConversions
import scala.slick.driver.H2Driver.simple._
import scala.slick.lifted.ProvenShape

case class User(
  identityId: String,
  firstName: String,
  lastName: String,
  fullName: String,
  email: Option[String],
  avatarUrl: Option[String],
  authMethod: AuthenticationMethod,
  oAuth1Info: Option[OAuth1Info],
  oAuth2Info: Option[OAuth2Info],
  passwordInfo: Option[PasswordInfo] = None,
  providerId: String = "userpass",
  uid: Option[Long] = None
)

class Users(tag: Tag) extends Table[User](tag, "user") {
  import scala.slick.driver.H2Driver

  implicit def string2AuthenticationMethod: H2Driver.BaseColumnType[AuthenticationMethod] =
    MappedColumnType.base[AuthenticationMethod, String] (
      authenticationMethod => authenticationMethod.method,
      string => AuthenticationMethod(string)
    )

  implicit def tuple2OAuth1Info(tuple: (Option[String], Option[String])): Option[OAuth1Info] = tuple match {
    case (Some(token), Some(secret)) => Some(OAuth1Info(token, secret))
    case _ => None
  }

  implicit def tuple2OAuth2Info(tuple: (Option[String], Option[String], Option[Int], Option[String])): Option[OAuth2Info] = tuple match {
    case (Some(token), tokenType, expiresIn, refreshToken) => Some(OAuth2Info(token, tokenType, expiresIn, refreshToken))
    case _ => None
  }

  def uid          = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def userId       = column[String]("userId")
  def providerId   = column[String]("providerId")
  def email        = column[Option[String]]("email")
  def firstName    = column[String]("firstName")
  def lastName     = column[String]("lastName")
  def fullName     = column[String]("fullName")
  def authMethod   = column[AuthenticationMethod]("authMethod")
  def avatarUrl    = column[Option[String]]("avatarUrl")

  // oAuth 1
  def token        = column[Option[String]]("token")
  def secret       = column[Option[String]]("secret")

  // oAuth 2
  def accessToken  = column[Option[String]]("accessToken")
  def tokenType    = column[Option[String]]("tokenType")
  def expiresIn    = column[Option[Int]]("expiresIn")
  def refreshToken = column[Option[String]]("refreshToken")

  def * : ProvenShape[User] = {
    val shapedValue = (uid.?,
      userId,
      providerId,
      firstName,
      lastName,
      fullName,
      email,
      avatarUrl,
      authMethod,
      token,
      secret,
      accessToken,
      tokenType,
      expiresIn,
      refreshToken).shaped

    shapedValue.<>({
      tuple =>
        User(
          uid = tuple._1,
          identityId = tuple._2,
          firstName = tuple._4,
          lastName = tuple._5,
          fullName = tuple._6,
          email = tuple._7,
          avatarUrl = tuple._8,
          authMethod = tuple._9,
          oAuth1Info = (tuple._10, tuple._11),
          oAuth2Info = (tuple._12, tuple._13, tuple._14, tuple._15)
    )}, {
      (u: User) =>
        Some {(
            u.uid,
            u.identityId,
            u.providerId,
            u.firstName,
            u.lastName,
            u.fullName,
            u.email,
            u.avatarUrl,
            u.authMethod,
            u.oAuth1Info.map(_.token),
            u.oAuth1Info.map(_.secret),
            u.oAuth2Info.map(_.accessToken),
            u.oAuth2Info.flatMap(_.tokenType),
            u.oAuth2Info.flatMap(_.expiresIn),
            u.oAuth2Info.flatMap(_.refreshToken)
        )}
    })
  }
}

import securesocial.core.providers.MailToken

class MailTokens(tag: Tag) extends Table[MailToken](tag, "token") {
  def uuid = column[String]("uuid")
  def email = column[String]("email")
  def creationTime = column[DateTime]("creationTime")
  def expirationTime = column[DateTime]("expirationTime")
  def isSignUp = column[Boolean]("isSignUp")

  def * : ProvenShape[MailToken] = {
    val shapedValue = (uuid, email, creationTime, expirationTime, isSignUp).shaped
    shapedValue.<>({
      tuple =>
        MailToken(uuid = tuple._1,
          email = tuple._2,
          creationTime = tuple._3,
          expirationTime = tuple._4,
          isSignUp = tuple._5)
    }, {
      (t: MailToken) =>
        Some {
          (t.uuid,
            t.email,
            t.creationTime,
            t.expirationTime,
            t.isSignUp)
        }
    })
  }
}

trait WithDefaultSession {
  def withSession[T](block: (Session => T)) = {
    val conf = play.api.Play.maybeApplication.map(_.configuration).getOrElse(play.api.Configuration.empty)
    val databaseURL      = conf.getString("db.default.url").get
    val databaseDriver   = conf.getString("db.default.driver").get
    val databaseUser     = conf.getString("db.default.user").getOrElse("")
    val databasePassword = conf.getString("db.default.password").getOrElse("")

    val database = Database.forURL(url=databaseURL, driver=databaseDriver, user=databaseUser, password=databasePassword)

    database withSession { session =>
      block(session)
    }
  }
}

object Tables extends WithDefaultSession {
  val MailTokens = new TableQuery[MailTokens](new MailTokens(_)) {
    def findById(tokenId: String): Option[MailToken] = withSession {
      implicit session =>
        val q = for {
          token <- this if token.uuid === tokenId
        } yield token
        q.firstOption
    }

    def save(token: MailToken): MailToken = withSession { implicit session =>
        findById(token.uuid) match {
          case None =>
            this.insert(token)
            token

          case Some(existingToken) =>
            val tokenRow = for {
                             t <- this if t.uuid === existingToken.uuid
                           } yield t

            val updatedToken = token.copy(uuid = existingToken.uuid)
            tokenRow.update(updatedToken)
            updatedToken
        }
    }

    def delete(uuid: String) = withSession { implicit session =>
        val q = for {
                  t <- this if t.uuid === uuid
                } yield t
        q.delete
    }

    def deleteExpiredTokens(currentDate: DateTime) = withSession { implicit session =>
        val q = for {
                  t <- this if t.expirationTime < currentDate
                } yield t
        q.delete
    }
  }

  val Users = new TableQuery[Users](new Users(_)) {
    def autoInc = this returning this.map(_.uid)

    def findById(id: Long) = withSession { implicit session =>
        val q = for {
                  user <- this if user.uid === id
                } yield user
        q.firstOption
    }

    def findByEmailAndProvider(email: String, providerId: String): Option[User] = withSession { implicit session =>
        this.filter(x => x.email === email && x.providerId === providerId).firstOption
    }

    def findByIdentityId(userId: String, providerId: String="userpass"): Option[User] = withSession { implicit session =>
      filter(x => x.userId === userId && x.providerId === providerId).firstOption
    }

    def all = withSession { implicit session => this.list
    }

    def save(user: User): User = withSession { implicit session =>
        findByIdentityId(user.identityId) match {
          case None =>
            val uid = autoInc.insert(user)
            user.copy(uid = Some(uid))

          case Some(existingUser) =>
            val userRow = for {
              u <- this if u.uid === existingUser.uid
            } yield u

            val updatedUser = user.copy(uid=existingUser.uid)
            userRow.update(updatedUser)
            updatedUser

        }
    }
  }
}
