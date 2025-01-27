package spp.platform.core.storage

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisAPI
import spp.platform.core.SourceStorage
import spp.protocol.auth.*
import spp.protocol.developer.Developer
import java.nio.charset.StandardCharsets

class RedisStorage : CoreStorage {

    lateinit var redis: RedisAPI

    suspend fun init(vertx: Vertx, config: JsonObject) {
        val sdHost = config.getJsonObject("redis").getString("host")
        val sdPort = config.getJsonObject("redis").getString("port")
        val redisClient = Redis.createClient(vertx, "redis://$sdHost:$sdPort").connect().await()
        redis = RedisAPI.api(redisClient)
    }

    override suspend fun getDevelopers(): List<Developer> {
        val devIds = redis.smembers("developers:ids").await()
        return devIds.map { Developer(it.toString(StandardCharsets.UTF_8)) }
    }

    override suspend fun getDeveloperByAccessToken(token: String): Developer? {
        val devId = redis.get("developers:access_tokens:$token").await()
            ?.toString(StandardCharsets.UTF_8) ?: return null
        return Developer(devId)
    }

    override suspend fun hasRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return redis.sismember("roles", role.roleName).await().toBoolean()
    }

    override suspend fun removeRole(role: DeveloperRole): Boolean {
        getRolePermissions(role).forEach {
            removePermissionFromRole(role, it)
        }
        return redis.srem(listOf("roles", role.roleName)).await().toBoolean()
    }

    override suspend fun addRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return redis.sadd(listOf("roles", role.roleName)).await().toBoolean()
    }

    override suspend fun hasDeveloper(id: String): Boolean {
        return redis.sismember("developers:ids", id).await().toBoolean()
    }

    override suspend fun addDeveloper(id: String, token: String): Developer {
        redis.sadd(listOf("developers:ids", id)).await()
        redis.set(listOf("developers:access_tokens:$token", id)).await()
        redis.sadd(listOf("developers:access_tokens", token)).await()
        redis.set(listOf("developers:ids:$id:access_token", token)).await()
        SourceStorage.addRoleToDeveloper(id, DeveloperRole.ROLE_USER)
        return Developer(id, token)
    }

    override suspend fun removeDeveloper(id: String) {
        val accessToken = getAccessToken(id)
        redis.srem(listOf("developers:ids", id)).await()
        redis.del(listOf("developers:access_tokens:$accessToken")).await()
        redis.srem(listOf("developers:access_tokens", accessToken)).await()
        redis.del(listOf("developers:ids:$id:access_token")).await()
        redis.del(listOf("developers:$id:roles")).await()
    }

    private suspend fun getAccessToken(id: String): String {
        return redis.get("developers:ids:$id:access_token").await().toString(StandardCharsets.UTF_8)
    }

    override suspend fun setAccessToken(id: String, accessToken: String) {
        //remove existing token
        val existingToken = redis.get("developers:ids:$id:access_token").await()
        if (existingToken != null) {
            val existingTokenStr = existingToken.toString(StandardCharsets.UTF_8)
            if (existingTokenStr.equals(accessToken)) {
                return //no change in access token; ignore
            } else {
                redis.srem(listOf("developers:access_tokens", existingTokenStr)).await()
                redis.del(listOf("developers:access_tokens:$existingToken")).await()
            }
        } else {
            //add developer first
            redis.sadd(listOf("developers:ids", id)).await()
        }

        //set new token
        redis.set(listOf("developers:access_tokens:$accessToken", id)).await()
        redis.sadd(listOf("developers:access_tokens", accessToken)).await()
        redis.set(listOf("developers:ids:$id:access_token", accessToken)).await()
    }

    override suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        return redis.smembers("developers:$developerId:roles").await()
            .map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }
    }

    override suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val accessPermissions = redis.smembers("roles:${role.roleName}:access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    override suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissions = redis.smembers("access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    override suspend fun hasAccessPermission(id: String): Boolean {
        return redis.sismember("access_permissions", id).await().toBoolean()
    }

    override suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissions = redis.get("access_permissions:$id").await()
        val dataObject = JsonObject(accessPermissions.toString(StandardCharsets.UTF_8))
        return AccessPermission(
            id,
            dataObject.getJsonArray("locationPatterns").map { it.toString() },
            AccessType.valueOf(dataObject.getString("type"))
        )
    }

    override suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        redis.sadd(listOf("access_permissions", id)).await()
        redis.set(
            listOf(
                "access_permissions:$id",
                JsonObject()
                    .put("locationPatterns", locationPatterns)
                    .put("type", type.name)
                    .toString()
            )
        ).await()
    }

    override suspend fun removeAccessPermission(id: String) {
        getRoles().forEach {
            removeAccessPermissionFromRole(id, it)
        }
        redis.srem(listOf("access_permissions", id)).await()
        redis.del(listOf("access_permissions:$id")).await()
    }

    override suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    override suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    override suspend fun getDataRedactions(): Set<DataRedaction> {
        val roles = redis.smembers("data_redactions").await()
        return roles.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    override suspend fun hasDataRedaction(id: String): Boolean {
        return redis.sismember("data_redactions", id).await().toBoolean()
    }

    override suspend fun getDataRedaction(id: String): DataRedaction {
        val permission = redis.get("data_redactions:$id").await()
        return DataRedaction(
            id,
            permission.toString(StandardCharsets.UTF_8)
        )
    }

    override suspend fun addDataRedaction(id: String, redactionPattern: String) {
        redis.sadd(listOf("data_redactions", id)).await()
        redis.set(listOf("data_redactions:$id", redactionPattern)).await()
    }

    override suspend fun removeDataRedaction(id: String) {
        getRoles().forEach {
            removeDataRedactionFromRole(id, it)
        }
        redis.srem(listOf("data_redactions", id)).await()
        redis.del(listOf("data_redactions:$id")).await()
    }

    override suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    override suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    override suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val dataRedactions = redis.smembers("roles:${role.roleName}:data_redactions").await()
        return dataRedactions.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    override suspend fun getRoles(): Set<DeveloperRole> {
        val roles = redis.smembers("roles").await()
        return roles.map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    override suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        redis.sadd(listOf("developers:$id:roles", role.roleName)).await()
    }

    override suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        redis.srem(listOf("developers:$id:roles", role.roleName)).await()
    }

    override suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        redis.sadd(listOf("roles", role.roleName)).await()
        redis.sadd(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    override suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        redis.srem(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    override suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val permissions = redis.smembers("roles:${role.roleName}:permissions").await()
        return permissions.map { RolePermission.valueOf(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }
}
