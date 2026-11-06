package com.local.release.web;

import com.local.release.service.MobileSigningCatalog;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apps/signing-profiles")
public class MobileSigningController {
  private final MobileSigningCatalog catalog;
  public MobileSigningController(MobileSigningCatalog catalog){this.catalog=catalog;}
  @GetMapping public MobileSigningCatalog.Catalog list(){return catalog.list();}
}
