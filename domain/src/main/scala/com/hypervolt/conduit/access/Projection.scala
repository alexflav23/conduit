package com.hypervolt.conduit.access

import io.circe.Json

// Response projection (doc 05 §3): after authorisation, strip any field whose data layer is not in the
// principal's viewable layers for that object. Unclassified fields are always visible. Applied uniformly
// to typed columns and to keys inside the governed `attributes` bag.
object Projection {

  def visibleLayers(principal: Principal, objectType: String): Set[DataLayer] =
    principal.grants
      .flatMap(_.permissions)
      .filter(p => p.objectType == objectType && p.action == Action.View)
      .flatMap(_.viewableLayers)
      .toSet

  def project(
      objectType: String,
      fieldLayers: Map[(String, String), DataLayer],
      allowedLayers: Set[DataLayer],
      row: Json
  ): Json =
    row.asObject.fold(row) { obj =>
      Json.fromJsonObject(obj.filterKeys { field =>
        fieldLayers.get((objectType, field)) match {
          case None        => true // unclassified → always visible
          case Some(layer) => allowedLayers.contains(layer)
        }
      })
    }

  // Convenience: project for a principal using the seed field map.
  def projectFor(principal: Principal, objectType: String, row: Json): Json =
    project(objectType, FieldLayerMap.seed, visibleLayers(principal, objectType), row)
}
