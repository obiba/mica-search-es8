/*
 * Copyright (c) 2026 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica;

import org.obiba.mica.spi.search.IndexFieldMapping;

/**
 * Index field mapping with the plugin specific sort information.
 */
public interface ESIndexFieldMapping extends IndexFieldMapping {

  String SORT_SUFFIX = ".sort";

  /**
   * Whether the field has the case-insensitive sort sub-field in the index mapping and case-insensitive
   * sorting is enabled.
   */
  boolean isSortable(String fieldName);

  /**
   * The field to sort on: the sort sub-field when the field is sortable, the field itself otherwise.
   */
  default String getSortField(String fieldName) {
    return isSortable(fieldName) ? fieldName + SORT_SUFFIX : fieldName;
  }
}
