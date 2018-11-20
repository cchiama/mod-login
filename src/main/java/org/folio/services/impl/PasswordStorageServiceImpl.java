package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.SQLConnection;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;

import java.util.Optional;

import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_CREDENTIALS;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_PW;

public class PasswordStorageServiceImpl implements PasswordStorageService {

  private static final String ID_FIELD = "'id'";
  private static final String USER_ID_FIELD = "'userId'";
  private static final String PW_ACTION_ID = "id";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";

  private final Logger logger = LoggerFactory.getLogger(PasswordStorageServiceImpl.class);
  private final Vertx vertx;

  public PasswordStorageServiceImpl(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public PasswordStorageService savePassword(String tenantId, JsonObject passwordEntity,
                                             Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      String id = passwordEntity.getString(PW_ACTION_ID);
      PasswordCreate passwordCreate = passwordEntity.mapTo(PasswordCreate.class);
      PostgresClient.getInstance(vertx, tenantId).save(SNAPSHOTS_TABLE_PW, id, passwordCreate, true,
        postReply -> {
          if (postReply.failed()) {
            String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
              "saving the logging event to the db", postReply.cause().getMessage());
            logger.error(errorMessage);
            asyncHandler.handle(Future.failedFuture(postReply.cause()));
            return;
          }
          asyncHandler.handle(Future.succeededFuture(JsonObject.mapFrom(passwordCreate)));
        });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "creating new password entity", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public PasswordStorageService findPasswordEntityById(String tenantId, String actionId,
                                                       Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      Criterion criterion = getCriterionId(actionId, ID_FIELD);
      PostgresClient.getInstance(vertx, tenantId)
        .get(SNAPSHOTS_TABLE_PW, PasswordCreate.class, criterion, true, false,
          getReply -> {
            if (getReply.failed()) {
              asyncHandler.handle(Future.succeededFuture(null));
              return;
            }
            Optional<PasswordCreate> passwordCreateOpt = getReply.result().getResults().stream().findFirst();
            if (!passwordCreateOpt.isPresent()) {
              asyncHandler.handle(Future.succeededFuture(null));
              return;
            }

            PasswordCreate passwordCreate = passwordCreateOpt.get();
            asyncHandler.handle(Future.succeededFuture(JsonObject.mapFrom(passwordCreate)));
          });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "find the password entity by id", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public PasswordStorageService resetPassword(String tenantId, JsonObject resetActionEntity,
                                              Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      PasswordReset passwordReset = resetActionEntity.mapTo(PasswordReset.class);
      PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

      pgClient.startTx(beginTx ->
        findUserIdByActionId(asyncHandler, passwordReset, pgClient, beginTx));
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "reset the password action", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  /**
   * Find user by id in `auth_password_action` table
   */
  private void findUserIdByActionId(Handler<AsyncResult<JsonObject>> asyncHandler, PasswordReset passwordReset,
                                    PostgresClient pgClient, AsyncResult<SQLConnection> beginTx) {
    String actionId = passwordReset.getPasswordResetActionId();
    Criterion criterionId = getCriterionId(actionId, ID_FIELD);

    pgClient.get(beginTx, SNAPSHOTS_TABLE_PW, PasswordCreate.class, criterionId, true, false,
      reply -> {
        if (reply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        Optional<PasswordCreate> passwordCreateOpt = reply.result().getResults().stream().findFirst();
        if (!passwordCreateOpt.isPresent()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(null)));
          return;
        }

        String userId = passwordCreateOpt.get().getUserId();
        findUserCredentialById(asyncHandler, passwordReset, pgClient, beginTx, userId);
      });
  }

  /**
   * Find user's credential by userId in `auth_credentials` table
   */
  private void findUserCredentialById(Handler<AsyncResult<JsonObject>> asyncHandler,
                                      PasswordReset resetAction, PostgresClient pgClient,
                                      AsyncResult<SQLConnection> beginTx, String userId) {
    Criterion criterion = getCriterionId(userId, USER_ID_FIELD);
    pgClient.get(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, Credential.class, criterion, true, false,
      getReply -> {
        if (getReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        Optional<Credential> credentialOpt = getReply.result().getResults()
          .stream().findFirst();
        if (!credentialOpt.isPresent()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(null)));
          return;
        }

        Credential userCredential = createCredential(resetAction, credentialOpt.get());
        changeUserCredential(pgClient, beginTx, asyncHandler, resetAction, userCredential);
      });
  }


  /**
   * Save a new user's credential
   */
  private void changeUserCredential(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                    Handler<AsyncResult<JsonObject>> asyncHandler,
                                    PasswordReset passwordReset, Credential userCredential) {
    String credId = userCredential.getId();
    Criterion criterionId = getCriterionId(credId, ID_FIELD);
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, criterionId,
      deleteReply -> {
        if (deleteReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        int resultCode = deleteReply.result().getUpdated();
        if (resultCode <= 0) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(null)));
          return;
        }

        pgClient.save(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, credId, userCredential,
          putReply -> {
            if (putReply.failed() || !putReply.result().equals(credId)) {
              pgClient.rollbackTx(beginTx,
                rollbackTx ->
                  asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
              return;
            }

            deletePasswordActionById(pgClient, beginTx, asyncHandler, passwordReset);
          });
      });
  }

  /**
   * Delete the password action by actionId
   */
  private void deletePasswordActionById(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                        Handler<AsyncResult<JsonObject>> asyncHandler,
                                        PasswordReset resetAction) {
    String actionId = resetAction.getPasswordResetActionId();
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_PW, getCriterionId(actionId, ID_FIELD),
      deleteReply -> {
        if (deleteReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        int resultCode = deleteReply.result().getUpdated();
        if (resultCode <= 0) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(null)));
          return;
        }

        pgClient.endTx(beginTx,
          endTx -> {
            JsonObject passwordResetJson = JsonObject.mapFrom(resetAction);
            asyncHandler.handle(Future.succeededFuture(passwordResetJson));
          });
      });
  }

  /**
   * Create a new user's credential
   *
   * @param passwordReset password action with a new user's password
   * @param cred          user's credential
   * @return new user's credential
   */
  private Credential createCredential(PasswordReset passwordReset, Credential cred) {
    AuthUtil authUtil = new AuthUtil();
    String password = passwordReset.getNewPassword();
    String newSalt = authUtil.getSalt();
    String newHash = authUtil.calculateHash(password, newSalt);
    return cred
      .withHash(newHash)
      .withSalt(newSalt);
  }

  /**
   * Builds criterion wrapper
   *
   * @param actionId id
   * @return Criterion object
   */
  private Criterion getCriterionId(String actionId, String field) {
    Criteria criteria = new Criteria()
      .addField(field)
      .setOperation(Criteria.OP_EQUAL)
      .setValue(actionId);
    return new Criterion(criteria);
  }
}